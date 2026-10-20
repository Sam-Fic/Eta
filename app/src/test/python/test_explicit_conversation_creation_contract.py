"""Explicit new-conversation wiring; complements JVM navigation/Store tests."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
APP = ROOT / "app/src/main/kotlin/io/github/mangi/eta/ui/app"


def method(text, name):
    match = re.search(r"^    (?:private )?fun " + re.escape(name) + r"\(", text, re.M)
    if match is None:
        raise AssertionError(name)
    following = re.search(r"^    (?:private )?(?:suspend )?fun ", text[match.end():], re.M)
    end = match.end() + following.start() if following else len(text)
    return text[match.start():end]


class ExplicitConversationCreationContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = (APP / "AgentAppState.kt").read_text()
        cls.create = method(cls.source, "createConversation")

    def test_new_assigns_an_id_and_publishes_before_persistence(self):
        self.assertIn("val conversationId = newConversationId()", self.create)
        self.assertNotIn("selectedConversationId = null", self.create)
        self.assertIn("selectedConversationId = conversationId", self.create)
        publish = self.create.index("updateConversation(conversationId, homeState)")
        self.assertLess(publish, self.create.index("refreshConversationSummaries()"))
        self.assertLess(publish, self.create.index("persistConversations()"))

    def test_configuration_failure_does_not_publish_a_half_created_conversation(self):
        guard = self.create.index("if (!bindSubAgentDraft(conversationId)) return")
        self.assertLess(self.create.index("if (!beginNewSubAgentDraft()) return"), guard)
        self.assertLess(guard, self.create.index("selectedConversationId = conversationId"))
        self.assertIn("rejectConversationArchiveMutation(protectStoppingRun = false)", self.create)

    def test_new_invalidates_pending_lazy_selection(self):
        selected = self.create.index("selectedConversationId = conversationId")
        for invalidation in ("conversationSelectionVersion += 1", "conversationSelectionJob?.cancel()",
                             "fileAttachmentOwnerVersion += 1"):
            self.assertLess(self.create.index(invalidation), selected)

    def test_folder_is_assigned_and_blank_title_is_not_hardened(self):
        self.assertIn("pendingNewConversationFolderId = selectedFolderId", self.create)
        self.assertLess(self.create.index("assignPendingFolder(conversationId)"),
                        self.create.index("persistConversations()"))
        self.assertNotIn("conversationTitles =", self.create)
        self.assertNotIn("conversationDrafts.promote", self.create)
        self.assertNotIn("conversationDrafts.clear", self.create)

    def test_startup_placeholder_and_existing_send_promotion_remain_separate(self):
        draft = method(self.source, "newDraftChatState")
        self.assertNotIn("newConversationId()", draft)
        self.assertNotIn("persistConversations()", draft)
        send = method(self.source, "sendCurrentMessage")
        self.assertIn("selectedConversationId ?: newConversationId().also", send)
        self.assertIn("conversationDrafts.promote(id)", send)

    def test_deleting_last_conversation_does_not_create_another_record(self):
        deletion = method(self.source, "deleteConversation")
        # 删除改为软删除（进回收站），选中项的落点交给统一的「删后切换」逻辑。
        self.assertIn("selectNextConversationAfterDeletion(excludeId = conversationId)", deletion)
        self.assertNotIn("newConversationId()", deletion)
        self.assertNotIn("createConversation()", deletion)
        switch = method(self.source, "selectNextConversationAfterDeletion")
        self.assertIn("selectedConversationId = null", switch)
        self.assertIn("homeState = newDraftChatState()", switch)
        self.assertNotIn("newConversationId()", switch)


if __name__ == "__main__":
    unittest.main()
