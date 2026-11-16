"""Composition root: builds and owns long-lived services (manual DI)."""

from __future__ import annotations

from dataclasses import dataclass

from app.core.config import Settings
from app.core.crypto import CredentialCipher
from app.core.rate_limit import SlidingWindowLimiter
from app.core.security import SingleUserIdentity
from app.db.session import Database
from app.services.agent.orchestrator import AgentOrchestrator
from app.services.agent.tools import ToolExecutor
from app.services.chat_service import ChatService
from app.services.coding import CommandRunner, GitService, WorkspaceManager
from app.services.knowledge import KnowledgeService
from app.services.model_gateway.gateway import ModelGateway
from app.services.openclaw import OpenClawClient
from app.services.provider_service import ProviderService
from app.services.research import ResearchService


@dataclass
class ServiceContainer:
    settings: Settings
    database: Database
    cipher: CredentialCipher
    identity: SingleUserIdentity
    rate_limiter: SlidingWindowLimiter
    gateway: ModelGateway
    providers: ProviderService
    chat: ChatService
    research: ResearchService
    knowledge: KnowledgeService
    workspace: WorkspaceManager
    runner: CommandRunner
    git: GitService
    tools: ToolExecutor
    orchestrator: AgentOrchestrator
    openclaw: OpenClawClient

    @classmethod
    async def create(cls, settings: Settings, database: Database) -> ServiceContainer:
        cipher = CredentialCipher(
            settings.credential_encryption_key, environment=settings.environment
        )
        identity = SingleUserIdentity(settings)
        gateway = ModelGateway(settings, cipher)
        providers = ProviderService(cipher, gateway)
        chat = ChatService(gateway, providers)
        research = ResearchService(settings)
        knowledge = KnowledgeService(gateway)
        workspace = WorkspaceManager(settings)
        runner = CommandRunner(settings)
        git = GitService(runner)
        tools = ToolExecutor(workspace, runner, git)
        openclaw = OpenClawClient(settings)
        orchestrator = AgentOrchestrator(
            database=database,
            gateway=gateway,
            providers=providers,
            tools=tools,
            workspace=workspace,
            research=research,
            settings=settings,
            openclaw=openclaw,
        )
        return cls(
            settings=settings,
            database=database,
            cipher=cipher,
            identity=identity,
            rate_limiter=SlidingWindowLimiter(settings.rate_limit_per_minute),
            gateway=gateway,
            providers=providers,
            chat=chat,
            research=research,
            knowledge=knowledge,
            workspace=workspace,
            runner=runner,
            git=git,
            tools=tools,
            orchestrator=orchestrator,
            openclaw=openclaw,
        )

    async def close(self) -> None:
        await self.orchestrator.shutdown()
        await self.gateway.close()
        await self.research.close()
        await self.database.dispose()
