"""Source contracts for remote-model refresh; no Kotlin build or Android execution."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[4]
KOTLIN = ROOT / "app/src/main/kotlin/io/github/mangi/eta"
RES = ROOT / "app/src/main/res"


class ProviderRemoteModelSyncContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ui = (KOTLIN / "ui/pages/providers/ProviderModelsTab.kt").read_text(encoding="utf-8")
        cls.repo = (KOTLIN / "data/repository/ModelRepository.kt").read_text(encoding="utf-8")
        # 拉取分支从读取远端目录开始，到统一的取消处理结束。
        cls.refresh = cls.ui.split("val fetchedCatalog = RemoteModelFetcher.fetchCatalog(provider)", 1)[1].split(
            "catch (cancelled: CancellationException)", 1
        )[0]
        cls.sync = cls.repo.split("suspend fun syncRemoteModels(", 1)[1].split(
            "suspend fun addSelectedRemoteModels(", 1
        )[0]

    def test_subscription_accounts_sync_the_whole_catalog_without_filtering(self):
        oauth = self.refresh.split("if (ProviderAuthModes.isOAuth(provider.authMode))", 1)[1].split(
            "val models = fetchedCatalog.models", 1
        )[0]
        self.assertIn("ModelRepository.syncRemoteCatalog(fetchedCatalog)", oauth)
        self.assertNotIn("isChatCapableModel", oauth)
        self.assertIn("R.string.provider_models_sync_summary", oauth)

    def test_success_syncs_without_adding_before_reading_latest_keys_and_candidates(self):
        sync_call = re.search(
            r"val syncResult = ModelRepository\.syncRemoteCatalog\(\s*"
            r"fetchedCatalog,\s*emptySet\(\),?\s*\)",
            self.refresh,
        )
        self.assertIsNotNone(sync_call)
        filtered = self.refresh.index("val chatModels = models.filter(RemoteModelFetcher::isChatCapableModel)")
        latest = self.refresh.index("val existingKeys = ModelRepository.modelsByProvider(provider.id)")
        fresh = self.refresh.index("val fresh = chatModels")
        empty_candidates = self.refresh.index("if (fresh.isEmpty())")
        self.assertLess(filtered, sync_call.start())
        self.assertLess(sync_call.end(), latest)
        self.assertLess(latest, fresh)
        self.assertLess(fresh, empty_candidates)
        self.assertIn(".map { it.modelId.trim().lowercase() }.toSet()", self.refresh[latest:fresh])
        self.assertIn(".filter { it.modelId.trim().lowercase() !in existingKeys }", self.refresh[fresh:empty_candidates])
        self.assertNotIn("provider.models", self.refresh)
        api_key = self.refresh.split("val models = fetchedCatalog.models", 1)[1]
        self.assertEqual(1, api_key.count("ModelRepository.syncRemoteCatalog("))

    def test_failed_fetch_returns_before_any_sync_and_cancel_is_rethrown(self):
        failure = self.ui.split("getOrElse { throwable ->", 1)[1].split(
            "if (ProviderAuthModes.isOAuth", 1
        )[0]
        self.assertIn("R.string.provider_error", failure)
        self.assertRegex(failure, r"return@launch\s*}\s*$")
        self.assertNotIn("syncRemoteCatalog", failure)
        self.assertNotIn("syncToRemotePreferences", failure)
        self.assertRegex(
            self.ui,
            r"catch \(cancelled: CancellationException\) \{\s*throw cancelled\s*}",
        )
        self.assertRegex(self.ui, r"finally \{\s*isFetching = false\s*}")

    def test_applied_sync_updates_runtime_even_when_no_models_were_added(self):
        self.assertRegex(
            self.refresh,
            r"if \(syncResult\.applied\) \{\s*"
            r"RuntimeConfigRepository\.syncToRemotePreferences\(EtaApp\.serviceInstance\)\s*}",
        )
        self.assertNotIn("addedCount", self.refresh)
        runtime = self.refresh.index("RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)")
        self.assertLess(runtime, self.refresh.index("val fresh = chatModels"))

    def test_selective_addition_dialog_is_still_the_only_new_model_import(self):
        self.assertIn("remoteCandidates = fresh", self.refresh)
        picker = self.ui.split("remoteCandidates?.let { candidates ->", 1)[1].split(
            "if (showBatchDeleteDialog)", 1
        )[0]
        self.assertIn("RemoteModelPickDialog(", picker)
        self.assertIn("models = candidates", picker)
        self.assertIn("onDismiss = { remoteCandidates = null }", picker)
        self.assertIn("onConfirm = { selected ->", picker)
        self.assertIn("ModelRepository.addSelectedRemoteModels(provider.id, selected)", picker)
        self.assertNotIn("addSelectedRemoteModels", self.refresh)

    def test_repository_keeps_default_import_and_empty_snapshot_protection(self):
        self.assertIn("includeNewModels: Boolean = true", self.sync)
        new_models = self.sync.split("remoteByKey.forEach { (key, remote) ->", 1)[1]
        self.assertIn("if (includeNewModels && key !in consumed)", new_models)
        empty_guard = self.sync.index("if (remoteByKey.isEmpty())")
        no_apply = self.sync.index("return@withLock RemoteModelSyncResult(applied = false)")
        existing = self.sync.index("val existing = currentModels(providerId)")
        self.assertLess(empty_guard, no_apply)
        self.assertLess(no_apply, existing)
        self.assertIn("stored.source != ModelSource.REMOTE -> add(stored)", self.sync)
        self.assertIn("ProviderRepository.repairSelection()", self.sync)

    def test_applied_refresh_reports_sync_even_when_there_are_no_candidates(self):
        self.assertRegex(
            self.refresh,
            r"val syncMessage = if \(syncResult\.applied\) \{\s*context\.getString\(\s*"
            r"R\.string\.provider_models_sync_summary,\s*syncResult\.fetchedCount,\s*"
            r"syncResult\.removedCount,?\s*\)\s*} else null",
        )
        no_candidates = self.refresh.split("if (fresh.isEmpty()) {", 1)[1].split("remoteCandidates = fresh", 1)[0]
        self.assertIn("if (chatModels.isEmpty())", no_candidates)
        self.assertIn("R.string.page_the_remote_end_did_not_return_a_usable_conversation__781487", no_candidates)
        self.assertIn("syncMessage ?: context.getString(R.string.provider_remote_none_new)", no_candidates)
        self.assertIn("else syncMessage", self.refresh)

    def test_default_english_and_chinese_summaries_use_matching_placeholders(self):
        for locale in ("values", "values-b+zh+Hans", "values-b+zh+Hant"):
            with self.subTest(locale=locale):
                root = ET.parse(RES / locale / "strings.xml").getroot()
                summary = root.find("string[@name='provider_models_sync_summary']")
                self.assertIsNotNone(summary)
                self.assertEqual(["%1$d", "%2$d"], re.findall(r"%\d+\$[a-z]", summary.text))


if __name__ == "__main__":
    unittest.main()
