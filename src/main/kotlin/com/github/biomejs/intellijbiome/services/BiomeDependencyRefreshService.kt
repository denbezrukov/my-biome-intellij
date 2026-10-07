package com.github.biomejs.intellijbiome.services

import com.github.biomejs.intellijbiome.BiomePackage
import com.github.biomejs.intellijbiome.BiomeTargetRun
import com.github.biomejs.intellijbiome.BiomeTargetRunBuilder
import com.github.biomejs.intellijbiome.ProcessCommandParameter
import com.github.biomejs.intellijbiome.lsp.BiomeLspServerDescriptor
import com.github.biomejs.intellijbiome.lsp.BiomeLspServerSupportProvider
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.execution.ExecutionException
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** Uses the public project-wide LSP restart only after the installed binaries work. */
@Service(Service.Level.PROJECT)
internal class BiomeDependencyRefreshService(private val project: Project, scope: CoroutineScope) {
    private val changes = Channel<Long>(Channel.CONFLATED)
    private val revision = AtomicLong()

    init {
        project.messageBus.connect(scope).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (project.isDisposed || !isAutomatic()) return
                val descriptors = servers().mapNotNull { it.descriptor as? BiomeLspServerDescriptor }
                if (events.any { event -> paths(event).any { path -> descriptors.any { relevant(path, it) } } }) {
                    changes.trySend(revision.incrementAndGet())
                }
            }
        })
        @OptIn(FlowPreview::class)
        scope.launch {
            changes.receiveAsFlow().debounce(500).collectLatest { generation -> verifyAndRefresh(generation) }
        }
    }

    private fun isAutomatic() = BiomeSettings.getInstance(project).configurationMode == ConfigurationMode.AUTOMATIC

    private fun servers() = LspServerManager.getInstance(project)
        .getServersForProvider(BiomeLspServerSupportProvider::class.java)

    private suspend fun verifyAndRefresh(generation: Long) {
        try {
            // An installation can finish while another root is still initializing or while
            // the set of open roots changes. Wait for a stable public snapshot, with a bound.
            withTimeoutOrNull(30_000) {
                while (!verifyInstalledVersions(generation)) delay(250)
            }
        } catch (_: ExecutionException) {
            // Keep the working server during an incomplete install or interpreter change.
        } catch (_: IOException) {
            // A disappearing/replaced executable is not a successful installation.
        }
    }

    private suspend fun verifyInstalledVersions(generation: Long): Boolean {
        if (revision.get() != generation) return true
        val snapshot = readAction { captureSnapshot() } ?: return false
        if (snapshot.candidates.isEmpty()) return true
        val verified = withContext(Dispatchers.IO) {
            snapshot.candidates.map { candidate ->
                val identity = identity(candidate.selection.executable)
                val version = BiomePackage(project).versionNumber(candidate.probe)
                if (identity != identity(candidate.selection.executable)) return@withContext null
                Verified(candidate, identity, version)
            }
        } ?: return false
        if (verified.none { it.version != it.candidate.selection.version }) return true
        // A package replacement can continue while another root is being checked.
        if (!withContext(Dispatchers.IO) { verified.all { it.identity == identity(it.candidate.selection.executable) } }) return false
        return withContext(Dispatchers.EDT) {
            coroutineContext.ensureActive()
            if (project.isDisposed || !isAutomatic() || revision.get() != generation) return@withContext true
            val current = readAction { captureSnapshot() } ?: return@withContext false
            if (current.servers != snapshot.servers || current.interpreter != snapshot.interpreter ||
                current.candidates.map { it.selection }.toSet() != snapshot.candidates.map { it.selection }.toSet()) return@withContext false
            // A relevant event invalidates this verification before the next debounce emits.
            if (revision.get() != generation) return@withContext true
            // Build 253 provides only a provider-wide restart. Other projects are untouched.
            LspServerManager.getInstance(project).stopAndRestartIfNeeded(BiomeLspServerSupportProvider::class.java)
            true
        }
    }

    /** null requests a bounded retry; an empty candidate list means there is no safe work. */
    private fun captureSnapshot(): Snapshot? {
        val noWork = Snapshot(emptySet(), emptyList(), null)
        if (project.isDisposed || !isAutomatic()) return noWork
        val managed = servers().toSet()
        val openFiles = FileEditorManager.getInstance(project).openFiles
        val active = managed.mapNotNull { server ->
            val descriptor = server.descriptor as? BiomeLspServerDescriptor ?: return noWork
            val file = openFiles.firstOrNull { descriptor.isSupportedFile(it) } ?: return@mapNotNull null
            Triple(server, descriptor, file)
        }
        // Idle servers, including retained failed instances, cannot block an open root.
        if (active.any { it.first.state == LspServerState.Initializing }) return null
        if (active.any { it.first.state != LspServerState.Running }) return noWork
        val manager = NodeJsInterpreterManager.getInstance(project)
        val interpreter = manager.interpreter
        val interpreterIdentity = InterpreterIdentity(manager.interpreterRef.referenceName,
            interpreter?.referenceName, interpreter?.javaClass?.name)
        val candidates = active.map { (server, descriptor, file) ->
            val root = descriptor.roots.singleOrNull()?.takeIf { it.isValid } ?: return noWork
            val executable = BiomePackage(project).binaryPath(root.path, file, false) ?: return noWork
            val version = server.initializeResult?.serverInfo?.version ?: return noWork
            val probe = BiomeTargetRunBuilder(project).getBuilder(executable, root.path)
                .addParameters(listOf(ProcessCommandParameter.Value("--version"))).build()
            Candidate(Selection(server, root, file, executable, version), probe)
        }
        return Snapshot(managed, candidates, interpreterIdentity)
    }

    private fun relevant(path: Path, descriptor: BiomeLspServerDescriptor): Boolean {
        val packageDirectory = Path.of(descriptor.executable).parent?.parent ?: return false
        if (path.startsWith(packageDirectory) || packageDirectory.startsWith(path)) return true
        return path.fileName?.toString() in manifestNames && descriptor.roots.any { Path.of(it.path).startsWith(path.parent) }
    }

    private fun paths(event: VFileEvent): List<Path> = when (event) {
        is VFilePropertyChangeEvent -> listOf(Path.of(event.oldPath), Path.of(event.newPath))
        is VFileMoveEvent -> listOf(Path.of(event.oldParent.path, event.file.name), Path.of(event.newParent.path, event.file.name))
        else -> listOf(Path.of(event.path))
    }

    private fun identity(executable: String): ExecutableIdentity {
        val path = Path.of(executable).toRealPath()
        return ExecutableIdentity(path, Files.size(path), Files.getLastModifiedTime(path))
    }

    private data class Snapshot(val servers: Set<LspServer>, val candidates: List<Candidate>, val interpreter: InterpreterIdentity?)
    private data class InterpreterIdentity(val configuredReference: String, val resolvedReference: String?, val implementation: String?)
    private data class Selection(val server: LspServer, val root: VirtualFile, val file: VirtualFile, val executable: String, val version: String)
    private data class Candidate(val selection: Selection, val probe: BiomeTargetRun)
    private data class ExecutableIdentity(val realPath: Path, val size: Long, val modified: FileTime)
    private data class Verified(val candidate: Candidate, val identity: ExecutableIdentity, val version: String)

    companion object {
        private val manifestNames = setOf("package.json", "package-lock.json", "npm-shrinkwrap.json", "pnpm-lock.yaml", "yarn.lock", "bun.lock", "bun.lockb")
    }
}
