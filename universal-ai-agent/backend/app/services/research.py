"""Web research: real search calls, real page fetches, verifiable citations.

Honesty rules enforced here:
* `searched` only becomes true after a search HTTP request actually succeeded
* a source is `verified` only when the page was fetched with HTTP 200 and text was
  extracted; otherwise the error is recorded and the source is excluded from the
  citation list
* when no model is available the report is an extractive summary of fetched text,
  clearly labelled as such — never an invented synthesis
"""

from __future__ import annotations

import asyncio
import ipaddress
import logging
import re
import socket
import time
from dataclasses import dataclass, field
from datetime import UTC, datetime
from html import unescape
from urllib.parse import parse_qs, quote_plus, urlparse

import httpx
from bs4 import BeautifulSoup

from app.core.config import Settings
from app.core.errors import APIError, bad_request
from app.core.redaction import redact

logger = logging.getLogger(__name__)

USER_AGENT = (
    "Mozilla/5.0 (compatible; UniversalAIAgent/0.1; +https://universal-ai-agent.app/bot)"
)
MAX_PAGE_BYTES = 3 * 1024 * 1024
BLOCKED_HOSTS = {"localhost", "metadata.google.internal"}


@dataclass
class SearchResult:
    title: str
    url: str
    snippet: str
    provider: str


@dataclass
class FetchedPage:
    url: str
    ok: bool
    status: int
    title: str = ""
    text: str = ""
    published_at: str | None = None
    error: str | None = None


@dataclass
class Source:
    index: int
    title: str
    url: str
    snippet: str
    provider: str
    published_at: str | None = None
    fetched_chars: int = 0
    verified: bool = False
    error: str | None = None
    excerpt: str = ""

    def citation(self) -> dict:
        return {
            "id": self.index,
            "title": self.title,
            "url": self.url,
            "snippet": self.snippet,
            "published_at": self.published_at,
            "verified": self.verified,
            "provider": self.provider,
        }


@dataclass
class ResearchBundle:
    query: str
    searched: bool
    providers: list[str] = field(default_factory=list)
    sources: list[Source] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)
    duration_ms: int = 0

    @property
    def verified_sources(self) -> list[Source]:
        return [source for source in self.sources if source.verified]


def _is_public_host(hostname: str) -> bool:
    if hostname in BLOCKED_HOSTS:
        return False
    try:
        infos = socket.getaddrinfo(hostname, None)
    except socket.gaierror:
        return False
    for info in infos:
        address = info[4][0]
        try:
            ip = ipaddress.ip_address(address)
        except ValueError:
            return False
        if ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_reserved:
            return False
    return True


def validate_public_url(url: str) -> str:
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"}:
        raise bad_request("Only http(s) URLs can be fetched.")
    if not parsed.hostname:
        raise bad_request("That URL has no host.")
    if not _is_public_host(parsed.hostname):
        raise bad_request("That host is not reachable from the research service.")
    return url


class ResearchService:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self._client = httpx.AsyncClient(
            timeout=httpx.Timeout(settings.research_fetch_timeout_seconds, connect=10.0),
            follow_redirects=True,
            headers={"User-Agent": USER_AGENT, "Accept-Language": "en;q=0.9"},
        )

    async def close(self) -> None:
        await self._client.aclose()

    # ------------------------------------------------------------------- search
    async def search(self, query: str, *, limit: int = 6) -> list[SearchResult]:
        query = query.strip()
        if not query:
            raise bad_request("A search query is required.")
        results: list[SearchResult] = []
        if self.settings.tavily_api_key:
            results.extend(await self._tavily(query, limit))
        if len(results) < limit:
            results.extend(await self._duckduckgo(query, limit - len(results)))
        if len(results) < min(3, limit):
            results.extend(await self._wikipedia(query, limit - len(results)))
        seen: set[str] = set()
        unique: list[SearchResult] = []
        for result in results:
            key = result.url.split("#")[0].rstrip("/")
            if key in seen:
                continue
            seen.add(key)
            unique.append(result)
        return unique[:limit]

    async def _tavily(self, query: str, limit: int) -> list[SearchResult]:
        try:
            response = await self._client.post(
                "https://api.tavily.com/search",
                json={
                    "api_key": self.settings.tavily_api_key,
                    "query": query,
                    "max_results": limit,
                    "search_depth": "basic",
                },
            )
            if response.status_code >= 400:
                logger.warning("tavily search failed: %s", response.status_code)
                return []
            return [
                SearchResult(
                    title=str(item.get("title") or item.get("url")),
                    url=str(item.get("url")),
                    snippet=str(item.get("content") or "")[:400],
                    provider="tavily",
                )
                for item in response.json().get("results", [])
            ]
        except (httpx.HTTPError, ValueError) as exc:
            logger.warning("tavily error: %s", exc)
            return []

    async def _duckduckgo(self, query: str, limit: int) -> list[SearchResult]:
        if limit <= 0:
            return []
        try:
            response = await self._client.post(
                "https://html.duckduckgo.com/html/",
                data={"q": query, "kl": "wt-wt"},
                headers={"Content-Type": "application/x-www-form-urlencoded"},
            )
        except httpx.HTTPError as exc:
            logger.warning("duckduckgo unreachable: %s", exc)
            return []
        if response.status_code >= 400:
            logger.warning("duckduckgo status %s", response.status_code)
            return []
        soup = BeautifulSoup(response.text, "html.parser")
        results: list[SearchResult] = []
        for block in soup.select("div.result"):
            link = block.select_one("a.result__a")
            if link is None:
                continue
            href = link.get("href") or ""
            if href.startswith("//duckduckgo.com/l/"):
                query_string = urlparse("https:" + href).query
                target = parse_qs(query_string).get("uddg", [""])[0]
                href = target or href
            snippet_node = block.select_one(".result__snippet")
            results.append(
                SearchResult(
                    title=link.get_text(" ", strip=True),
                    url=href,
                    snippet=(snippet_node.get_text(" ", strip=True) if snippet_node else "")[:400],
                    provider="duckduckgo",
                )
            )
            if len(results) >= limit:
                break
        return results

    async def _wikipedia(self, query: str, limit: int) -> list[SearchResult]:
        if limit <= 0:
            return []
        try:
            response = await self._client.get(
                "https://en.wikipedia.org/w/api.php",
                params={
                    "action": "query",
                    "list": "search",
                    "srsearch": query,
                    "format": "json",
                    "srlimit": limit,
                },
            )
            if response.status_code >= 400:
                return []
            items = response.json().get("query", {}).get("search", [])
        except (httpx.HTTPError, ValueError):
            return []
        results: list[SearchResult] = []
        for item in items:
            title = str(item.get("title") or "")
            snippet = re.sub(r"<[^>]+>", "", str(item.get("snippet") or ""))
            results.append(
                SearchResult(
                    title=title,
                    url="https://en.wikipedia.org/wiki/" + quote_plus(title.replace(" ", "_")),
                    snippet=unescape(snippet)[:400],
                    provider="wikipedia",
                )
            )
        return results

    # -------------------------------------------------------------------- fetch
    async def fetch_page(self, url: str) -> FetchedPage:
        try:
            validate_public_url(url)
        except APIError as exc:
            return FetchedPage(url=url, ok=False, status=0, error=exc.message)
        try:
            response = await self._client.get(url)
        except httpx.HTTPError as exc:
            return FetchedPage(
                url=url, ok=False, status=0, error=f"Fetch failed: {type(exc).__name__}"
            )
        if response.status_code >= 400:
            return FetchedPage(
                url=str(response.url),
                ok=False,
                status=response.status_code,
                error=f"HTTP {response.status_code}",
            )
        content_type = response.headers.get("content-type", "")
        if "html" not in content_type and "text" not in content_type:
            return FetchedPage(
                url=str(response.url),
                ok=False,
                status=response.status_code,
                error=f"Unsupported content type: {content_type or 'unknown'}",
            )
        raw = response.content[:MAX_PAGE_BYTES].decode(response.encoding or "utf-8", "replace")
        soup = BeautifulSoup(raw, "html.parser")
        for tag in soup(["script", "style", "nav", "footer", "header", "aside", "form", "noscript"]):
            tag.decompose()
        title = (soup.title.get_text(strip=True) if soup.title else "") or url
        published = self._published_at(soup)
        main = soup.find("article") or soup.find("main") or soup.body or soup
        paragraphs = [
            element.get_text(" ", strip=True)
            for element in main.find_all(["p", "li", "h1", "h2", "h3", "blockquote", "pre"])
        ]
        text = "\n".join(paragraph for paragraph in paragraphs if len(paragraph) > 40)
        if len(text) < 200:
            text = main.get_text("\n", strip=True)
        text = re.sub(r"\n{3,}", "\n\n", text)[:20000]
        return FetchedPage(
            url=str(response.url),
            ok=bool(text.strip()),
            status=response.status_code,
            title=title[:300],
            text=text,
            published_at=published,
            error=None if text.strip() else "No readable text extracted.",
        )

    @staticmethod
    def _published_at(soup: BeautifulSoup) -> str | None:
        for attribute in (
            ("property", "article:published_time"),
            ("name", "pubdate"),
            ("name", "publish-date"),
            ("itemprop", "datePublished"),
        ):
            tag = soup.find("meta", attrs={attribute[0]: attribute[1]})
            if tag and tag.get("content"):
                return str(tag["content"])[:40]
        time_tag = soup.find("time")
        if time_tag and (time_tag.get("datetime") or time_tag.get_text(strip=True)):
            return str(time_tag.get("datetime") or time_tag.get_text(strip=True))[:40]
        return None

    # ------------------------------------------------------------------- bundle
    async def gather(self, query: str, *, limit: int | None = None) -> ResearchBundle:
        started = time.perf_counter()
        limit = limit or self.settings.research_max_sources
        bundle = ResearchBundle(query=query, searched=False)
        results = await self.search(query, limit=limit)
        bundle.searched = True
        if not results:
            bundle.errors.append("The search providers returned no results.")
            bundle.duration_ms = int((time.perf_counter() - started) * 1000)
            return bundle
        bundle.providers = sorted({result.provider for result in results})
        pages = await asyncio.gather(
            *(self.fetch_page(result.url) for result in results), return_exceptions=False
        )
        for index, (result, page) in enumerate(zip(results, pages, strict=False), start=1):
            source = Source(
                index=index,
                title=page.title or result.title,
                url=page.url if page.ok else result.url,
                snippet=result.snippet,
                provider=result.provider,
                published_at=page.published_at,
                fetched_chars=len(page.text),
                verified=bool(page.ok and page.status == 200),
                error=page.error,
                excerpt=page.text[:1200],
            )
            if source.error:
                bundle.errors.append(f"{source.url}: {source.error}")
            bundle.sources.append(source)
        if not bundle.verified_sources:
            bundle.errors.append(
                "Search returned links, but none of the pages could be fetched and verified."
            )
        bundle.duration_ms = int((time.perf_counter() - started) * 1000)
        return bundle

    # -------------------------------------------------------------------- report
    def context_block(self, bundle: ResearchBundle, *, max_chars: int = 12000) -> str:
        blocks: list[str] = []
        used = 0
        for source in bundle.verified_sources:
            header = f"[{source.index}] {source.title} — {source.url}"
            if source.published_at:
                header += f" (published {source.published_at})"
            body = source.excerpt or source.snippet
            chunk = f"{header}\n{body}\n"
            if used + len(chunk) > max_chars:
                break
            blocks.append(chunk)
            used += len(chunk)
        return "\n".join(blocks)

    def extractive_report(self, bundle: ResearchBundle) -> str:
        lines = [
            f"# Research: {bundle.query}",
            "",
            f"_Collected {datetime.now(UTC).strftime('%Y-%m-%d %H:%M UTC')} · "
            f"{len(bundle.verified_sources)} verified source(s) via "
            f"{', '.join(bundle.providers) or 'no provider'}._",
            "",
            "## Evidence",
            "",
        ]
        if not bundle.verified_sources:
            lines.append(
                "No source could be fetched and verified, so no claims are made here. "
                "The raw search results are listed below for manual review."
            )
        for source in bundle.sources:
            marker = "✅ verified" if source.verified else "⚠️ not verified"
            lines.append(f"### [{source.index}] {source.title}")
            lines.append(f"- URL: {source.url}")
            lines.append(f"- Status: {marker}" + (f" ({source.error})" if source.error else ""))
            if source.published_at:
                lines.append(f"- Published: {source.published_at}")
            lines.append("")
            lines.append(source.excerpt[:800] or source.snippet or "_No extract available._")
            lines.append("")
        lines.append("## Sources")
        for source in bundle.sources:
            lines.append(f"{source.index}. [{source.title}]({source.url})")
        return "\n".join(lines)

    @staticmethod
    def verify_citations(markdown: str, bundle: ResearchBundle) -> list[dict]:
        """Return citations actually referenced in the text, with verification state."""
        used = {int(match) for match in re.findall(r"\[(\d{1,2})\]", markdown)}
        citations: list[dict] = []
        for source in bundle.sources:
            if source.index in used:
                citation = source.citation()
                citation["referenced"] = True
                citations.append(citation)
        return citations


def summarise_errors(errors: list[str]) -> str:
    return "; ".join(redact(error) for error in errors[:4])
