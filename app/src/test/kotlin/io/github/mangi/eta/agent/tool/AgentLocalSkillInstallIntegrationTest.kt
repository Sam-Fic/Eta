package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.skill.PublicGitHubSkillSource
import io.github.mangi.eta.agent.skill.SkillIndexService
import io.github.mangi.eta.agent.skill.SkillDirectoryMover
import io.github.mangi.eta.agent.skill.SkillLoader
import io.github.mangi.eta.agent.skill.SkillPackageInstaller
import io.github.mangi.eta.agent.skill.SkillResourceReader
import io.github.mangi.eta.core.AgentLogger
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import io.github.mangi.eta.data.db.EtaDatabase

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentLocalSkillInstallIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun setUp() {
        EtaDatabase.closeForTests()
        RuntimeEnvironment.getApplication().deleteDatabase("eta.db")
    }

    @Test
    fun explicitGitHubSelectionInstallsThroughCoreAndBecomesAvailableNextTurn() {
        val context = RuntimeEnvironment.getApplication() as Context
        val skillsRoot = temporaryFolder.newFolder("skills")
        val indexService = SkillIndexService(context, skillsRoot)
        val requestedUrls = mutableListOf<String>()
        val client = githubClient(requestedUrls)
        val source = PublicGitHubSkillSource(
            cacheRoot = temporaryFolder.newFolder("cache"),
            baseClient = client,
        )
        val tools = AgentLocalTools(
            context = context,
            logger = NoOpLogger,
            githubSkillSource = source,
            skillPackageInstaller = SkillPackageInstaller(skillsRoot, indexService),
            skillIndexService = indexService,
            skillLoader = SkillLoader(skillsRoot),
            skillResourceReader = SkillResourceReader(skillsRoot),
        )

        val inspection = tools.execute(
            AgentModelClient.ToolCall(
                id = "inspect-1",
                name = "skills_inspect_github",
                argumentsJson = JSONObject()
                    .put("repository", "example/skills")
                    .put("ref", "main")
                    .toString(),
            ),
        )
        assertTrue(inspection.content, JSONObject(inspection.content).getBoolean("ok"))

        val invalidSelection = tools.execute(
            AgentModelClient.ToolCall(
                id = "install-invalid",
                name = "skills_install_from_github",
                argumentsJson = JSONObject()
                    .put("repository", "example/skills")
                    .put("ref", "main")
                    .put("paths", JSONArray().put("skills/not-inspected"))
                    .put("replaceExisting", false)
                    .toString(),
            ),
        )
        assertEquals(
            "INVALID_SKILL_SELECTION",
            JSONObject(invalidSelection.content).getString("code"),
        )
        val result = tools.execute(
            AgentModelClient.ToolCall(
                id = "install-1",
                name = "skills_install_from_github",
                argumentsJson = JSONObject()
                    .put("repository", "example/skills")
                    .put("ref", "main")
                    .put("paths", JSONArray().put("skills/demo"))
                    .put("replaceExisting", false)
                    .toString(),
            ),
        )
        val json = JSONObject(result.content)

        assertTrue(json.toString(), json.getBoolean("ok"))
        assertEquals("next_turn", json.getString("available"))
        assertFalse(json.getBoolean("scriptsExecuted"))
        assertEquals("demo-skill", json.getJSONArray("installed").getJSONObject(0).getString("id"))
        assertFalse(json.getJSONArray("installed").getJSONObject(0).has("description"))
        assertTrue(requestedUrls.any { COMMIT_SHA in it })
        assertTrue(requestedUrls.any { "codeload.github.com/example/skills/zip/$COMMIT_SHA" in it })
        indexService.listSkillsForManagement(forceRefresh = true)

        val sameTurnList = JSONObject(
            tools.execute(
                AgentModelClient.ToolCall("list-1", "skills_list", "{}"),
            ).content,
        )
        assertEquals(0, sameTurnList.getInt("count"))
        val sameTurnRead = JSONObject(
            tools.execute(
                AgentModelClient.ToolCall(
                    "read-1",
                    "skills_read",
                    JSONObject().put("skillId", "demo-skill").toString(),
                ),
            ).content,
        )
        assertEquals("NEXT_TURN_REQUIRED", sameTurnRead.getString("code"))
        val sameTurnResource = JSONObject(
            tools.execute(
                AgentModelClient.ToolCall(
                    "resource-1",
                    "skills_read_resource",
                    JSONObject()
                        .put("skillId", "demo-skill")
                        .put("relativePath", "references/guide.md")
                        .toString(),
                ),
            ).content,
        )
        assertEquals("NEXT_TURN_REQUIRED", sameTurnResource.getString("code"))
        tools.close()

        val nextTurnEntries = indexService.listSkillsForManagement(forceRefresh = true)
            .filter { it.id == "demo-skill" && it.installed }
        val nextTurnTools = AgentLocalTools(
            context = context,
            logger = NoOpLogger,
            skillIndexService = indexService,
            skillLoader = SkillLoader(skillsRoot),
            skillResourceReader = SkillResourceReader(skillsRoot),
            runAvailableSkillIds = setOf("demo-skill"),
            runSkillEntries = nextTurnEntries,
        )
        val nextTurnRead = JSONObject(
            nextTurnTools.execute(
                AgentModelClient.ToolCall(
                    "read-2",
                    "skills_read",
                    JSONObject().put("skillId", "demo-skill").toString(),
                ),
            ).content,
        )
        assertTrue(nextTurnRead.toString(), nextTurnRead.getBoolean("ok"))
        val nextTurnResource = JSONObject(
            nextTurnTools.execute(
                AgentModelClient.ToolCall(
                    "resource-2",
                    "skills_read_resource",
                    JSONObject()
                        .put("skillId", "demo-skill")
                        .put("relativePath", "references/guide.md")
                        .toString(),
                ),
            ).content,
        )
        assertTrue(nextTurnResource.toString(), nextTurnResource.getBoolean("ok"))
        nextTurnTools.close()
    }

    @Test
    fun sameRunConflictCanBePreciselyReplayedWithoutPromptConfirmation() {
        val context = RuntimeEnvironment.getApplication() as Context
        val skillsRoot = temporaryFolder.newFolder("replace-skills")
        val existingRoot = File(skillsRoot, "demo-skill").also { it.mkdirs() }
        File(existingRoot, "SKILL.md").writeText(
            """
            ---
            name: demo-skill
            description: Existing demo Skill.
            ---

            # Old demo
            """.trimIndent(),
        )
        val indexService = SkillIndexService(context, skillsRoot)
        indexService.listSkillsForManagement(forceRefresh = true)
        val source = PublicGitHubSkillSource(
            cacheRoot = temporaryFolder.newFolder("replace-cache"),
            baseClient = githubClient(mutableListOf()),
        )
        val tools = AgentLocalTools(
            context = context,
            logger = NoOpLogger,
            githubSkillSource = source,
            skillPackageInstaller = SkillPackageInstaller(skillsRoot, indexService),
            skillIndexService = indexService,
            skillLoader = SkillLoader(skillsRoot),
            skillResourceReader = SkillResourceReader(skillsRoot),
            runAvailableSkillIds = setOf("demo-skill"),
        )
        val inspection = JSONObject(
            tools.execute(
                AgentModelClient.ToolCall(
                    id = "inspect-replace",
                    name = "skills_inspect_github",
                    argumentsJson = JSONObject()
                        .put("repository", "example/skills")
                        .put("ref", "main")
                        .toString(),
                ),
            ).content,
        )
        assertTrue(inspection.toString(), inspection.getBoolean("ok"))
        val conflict = JSONObject(
            tools.execute(
                AgentModelClient.ToolCall(
                    id = "conflict",
                    name = "skills_install_from_github",
                    argumentsJson = JSONObject()
                        .put("repository", "example/skills")
                        .put("ref", "main")
                        .put("paths", JSONArray().put("skills/demo"))
                        .put("replaceExisting", false)
                        .toString(),
                ),
            ).content,
        )
        assertEquals("SKILL_CONFLICT", conflict.getString("code"))

        val replacement = JSONObject(
            tools.execute(
                AgentModelClient.ToolCall(
                    id = "replace-1",
                    name = "skills_install_from_github",
                    argumentsJson = JSONObject()
                        .put("repository", "example/skills")
                        .put("ref", COMMIT_SHA)
                        .put("paths", JSONArray().put("skills/demo"))
                        .put("replaceExisting", true)
                        .put("expectedReplacementId", "demo-skill")
                        .toString(),
                ),
            ).content,
        )

        assertTrue(replacement.toString(), replacement.getBoolean("ok"))
        assertEquals(
            0,
            JSONObject(
                tools.execute(AgentModelClient.ToolCall("list", "skills_list", "{}")).content,
            ).getInt("count"),
        )
        val read = JSONObject(
            tools.execute(
                AgentModelClient.ToolCall(
                    "read",
                    "skills_read",
                    JSONObject().put("skillId", "demo-skill").toString(),
                ),
            ).content,
        )
        assertEquals("NEXT_TURN_REQUIRED", read.getString("code"))
        tools.close()
    }

    @Test
    fun commitFailureMakesAllSkillReadsUnavailableForTheTurn() {
        val context = RuntimeEnvironment.getApplication() as Context
        val skillsRoot = temporaryFolder.newFolder("failed-skills")
        val baselineRoot = File(skillsRoot, "baseline").also { it.mkdirs() }
        File(baselineRoot, "SKILL.md").writeText(
            """
            ---
            name: baseline
            description: Existing baseline Skill.
            ---

            # Baseline
            """.trimIndent(),
        )
        File(baselineRoot, "references/guide.md").apply {
            parentFile?.mkdirs()
            writeText("baseline guide")
        }
        val indexService = SkillIndexService(context, skillsRoot)
        indexService.listSkillsForManagement(forceRefresh = true)
        val source = PublicGitHubSkillSource(
            cacheRoot = temporaryFolder.newFolder("failed-cache"),
            baseClient = githubClient(mutableListOf()),
        )
        val failingInstaller = SkillPackageInstaller(
            skillsRoot = skillsRoot,
            indexService = indexService,
            directoryMover = SkillDirectoryMover { _, _ ->
                throw IOException("injected commit failure")
            },
        )
        val tools = AgentLocalTools(
            context = context,
            logger = NoOpLogger,
            githubSkillSource = source,
            skillPackageInstaller = failingInstaller,
            skillIndexService = indexService,
            skillLoader = SkillLoader(skillsRoot),
            skillResourceReader = SkillResourceReader(skillsRoot),
            runAvailableSkillIds = setOf("baseline"),
        )
        tools.execute(
            AgentModelClient.ToolCall(
                "inspect",
                "skills_inspect_github",
                JSONObject()
                    .put("repository", "example/skills")
                    .put("ref", "main")
                    .toString(),
            ),
        )

        val failure = JSONObject(
            tools.execute(
                AgentModelClient.ToolCall(
                    "install",
                    "skills_install_from_github",
                    JSONObject()
                        .put("repository", "example/skills")
                        .put("ref", "main")
                        .put("paths", JSONArray().put("skills/demo"))
                        .put("replaceExisting", false)
                        .toString(),
                ),
            ).content,
        )
        assertEquals("COMMIT_FAILED", failure.getString("code"))

        listOf(
            AgentModelClient.ToolCall("list", "skills_list", "{}"),
            AgentModelClient.ToolCall(
                "read",
                "skills_read",
                JSONObject().put("skillId", "baseline").toString(),
            ),
            AgentModelClient.ToolCall(
                "resource",
                "skills_read_resource",
                JSONObject()
                    .put("skillId", "baseline")
                    .put("relativePath", "references/guide.md")
                    .toString(),
            ),
        ).forEach { call ->
            assertEquals(
                call.name,
                "NEXT_TURN_REQUIRED",
                JSONObject(tools.execute(call).content).getString("code"),
            )
        }
        tools.close()
    }

    @Test
    fun localDirectorySkillInstallsThroughTheToolAndBecomesAvailableNextTurn() {
        val context = RuntimeEnvironment.getApplication() as Context
        val skillsRoot = temporaryFolder.newFolder("local-skills")
        val indexService = SkillIndexService(context, skillsRoot)
        val tools = AgentLocalTools(
            context = context,
            logger = NoOpLogger,
            skillPackageInstaller = SkillPackageInstaller(skillsRoot, indexService),
            skillIndexService = indexService,
            skillLoader = SkillLoader(skillsRoot),
            skillResourceReader = SkillResourceReader(skillsRoot),
        )
        val sourceDirectory = temporaryFolder.newFolder("downloads")
        val skillDirectory = File(sourceDirectory, "phone-skill")
        writeSkill(skillDirectory, "phone-skill", "SKILL.md content for the phone Skill.")
        File(skillDirectory, "references/guide.md").apply {
            parentFile?.mkdirs()
            writeText("guide")
        }

        val result = JSONObject(
            tools.execute(
                AgentModelClient.ToolCall(
                    id = "install-local",
                    name = "skills_install_local",
                    argumentsJson = JSONObject()
                        .put("directory", skillDirectory.absolutePath)
                        .toString(),
                ),
            ).content,
        )

        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals("local", result.getString("source"))
        assertEquals("next_turn", result.getString("available"))
        assertFalse(result.getBoolean("scriptsExecuted"))
        assertEquals(
            "phone-skill",
            result.getJSONArray("installed").getJSONObject(0).getString("id"),
        )

        val sameTurnList = JSONObject(
            tools.execute(AgentModelClient.ToolCall("list-local", "skills_list", "{}")).content,
        )
        assertEquals(0, sameTurnList.getInt("count"))
        tools.close()

        val nextTurnEntries = indexService.listSkillsForManagement(forceRefresh = true)
            .filter { it.id == "phone-skill" && it.installed }
        val nextTurnTools = AgentLocalTools(
            context = context,
            logger = NoOpLogger,
            skillIndexService = indexService,
            skillLoader = SkillLoader(skillsRoot),
            skillResourceReader = SkillResourceReader(skillsRoot),
            runAvailableSkillIds = setOf("phone-skill"),
            runSkillEntries = nextTurnEntries,
        )
        val nextTurnRead = JSONObject(
            nextTurnTools.execute(
                AgentModelClient.ToolCall(
                    "read-local",
                    "skills_read",
                    JSONObject().put("skillId", "phone-skill").toString(),
                ),
            ).content,
        )
        assertTrue(nextTurnRead.toString(), nextTurnRead.getBoolean("ok"))
        nextTurnTools.close()
    }

    @Test
    fun localDirectoryWithSeveralSkillsListsCandidatesWithoutInstallingAnything() {
        val context = RuntimeEnvironment.getApplication() as Context
        val skillsRoot = temporaryFolder.newFolder("multi-local-skills")
        val indexService = SkillIndexService(context, skillsRoot)
        val tools = AgentLocalTools(
            context = context,
            logger = NoOpLogger,
            skillPackageInstaller = SkillPackageInstaller(skillsRoot, indexService),
            skillIndexService = indexService,
            skillLoader = SkillLoader(skillsRoot),
            skillResourceReader = SkillResourceReader(skillsRoot),
        )
        val sourceDirectory = temporaryFolder.newFolder("multi-downloads")
        writeSkill(File(sourceDirectory, "one"), "one", "First local Skill.")
        writeSkill(File(sourceDirectory, "two"), "two", "Second local Skill.")

        val result = JSONObject(
            tools.execute(
                AgentModelClient.ToolCall(
                    id = "install-multi",
                    name = "skills_install_local",
                    argumentsJson = JSONObject()
                        .put("directory", sourceDirectory.absolutePath)
                        .toString(),
                ),
            ).content,
        )

        assertFalse(result.toString(), result.getBoolean("ok"))
        assertEquals("MULTIPLE_SKILLS_FOUND", result.getString("code"))
        assertEquals(2, result.getJSONArray("candidates").length())
        assertEquals(
            File(sourceDirectory, "one").absolutePath,
            result.getJSONArray("candidates").getJSONObject(0).getString("directory"),
        )
        assertTrue(
            indexService.listSkillsForManagement(forceRefresh = true)
                .none { it.id == "one" || it.id == "two" },
        )
        tools.close()
    }

    private fun writeSkill(directory: File, name: String, description: String) {
        directory.mkdirs()
        File(directory, "SKILL.md").writeText(
            """
            ---
            name: $name
            description: $description
            ---

            # $name
            """.trimIndent(),
        )
    }

    private fun githubClient(requestedUrls: MutableList<String>): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requestedUrls += request.url.toString()
                val body = when {
                    request.url.host != "api.github.com" ->
                        repositoryZip().toResponseBody("application/zip".toMediaType())
                    "/git/trees/" in request.url.encodedPath ->
                        JSONObject()
                            .put("truncated", false)
                            .put(
                                "tree",
                                JSONArray().put(
                                    JSONObject()
                                        .put("type", "blob")
                                        .put("path", "skills/demo/SKILL.md"),
                                ).put(
                                    JSONObject()
                                        .put("type", "blob")
                                        .put("path", "skills/other/SKILL.md"),
                                ),
                            )
                            .toString()
                            .toResponseBody("application/json".toMediaType())
                    else -> JSONObject()
                        .put("sha", COMMIT_SHA)
                        .put(
                            "commit",
                            JSONObject().put(
                                "tree",
                                JSONObject().put("sha", TREE_SHA),
                            ),
                        )
                        .toString()
                        .toResponseBody("application/json".toMediaType())
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body)
                    .build()
            }
            .build()

    private fun repositoryZip(): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("skills-$COMMIT_SHA/skills/demo/SKILL.md"))
            zip.write(
                """
                ---
                name: demo-skill
                description: Demo Skill installed by the GitHub tool integration test.
                ---

                # Demo Skill
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("skills-$COMMIT_SHA/skills/demo/references/guide.md"))
            zip.write("guide".toByteArray())
            zip.closeEntry()
        }
        bytes.toByteArray()
    }

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }

    private companion object {
        const val COMMIT_SHA = "1111111111111111111111111111111111111111"
        const val TREE_SHA = "2222222222222222222222222222222222222222"
    }
}
