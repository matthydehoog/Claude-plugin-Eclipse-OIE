package com.matthy.oie.claude.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ConversationStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static Conversation conversation(int userId, String question) {
        Conversation c = new Conversation(userId);
        c.record("question", question);
        c.record("tool", "oie_list_channels {}");
        c.record("text", "Channel **ADT in** has 3 errors.");
        return c;
    }

    @Test
    public void savesListsAndLoadsPerUser() throws Exception {
        ConversationStore store = new ConversationStore(folder.getRoot());
        Conversation mine = conversation(1, "Which channels have errors?");
        Conversation other = conversation(2, "Something else");
        store.save(mine, "{\"messages\":[]}");
        store.save(other, null);

        List<ConversationStore.Saved> list = store.list(1);
        assertEquals(1, list.size());
        assertEquals(mine.id, list.get(0).id);
        assertEquals("Which channels have errors?", list.get(0).title);

        ConversationStore.Saved loaded = store.load(1, mine.id);
        assertEquals(3, loaded.log.size());
        assertEquals("text", loaded.log.get(2).type);
        assertEquals("{\"messages\":[]}", loaded.history);
        assertEquals(mine.created, loaded.created);

        // another user's conversation, or a made-up ID, is not found
        assertNull(store.load(1, other.id));
        assertNull(store.load(1, "../2/" + other.id));
        assertNull(store.load(2, other.id).history);
    }

    @Test
    public void savingAgainReplacesTheFile() throws Exception {
        ConversationStore store = new ConversationStore(folder.getRoot());
        Conversation c = conversation(1, "First question");
        store.save(c, null);
        c.record("question", "Second question");
        store.save(c, null);

        assertEquals(1, store.list(1).size());
        assertEquals(4, store.load(1, c.id).log.size());
        assertEquals(1, new File(folder.getRoot(), "1").listFiles().length); // no .tmp left behind
    }

    @Test
    public void removesOldConversationsAndAllWhenSavingIsOff() throws Exception {
        ConversationStore store = new ConversationStore(folder.getRoot());
        Conversation old = conversation(1, "Old");
        Conversation recent = conversation(1, "Recent");
        store.save(old, null);
        store.save(recent, null);
        File oldFile = new File(new File(folder.getRoot(), "1"), old.id + ".json");
        assertTrue(oldFile.setLastModified(System.currentTimeMillis() - 10L * 24 * 3600 * 1000));

        assertEquals(1, store.removeOlderThan(7));
        assertEquals(1, store.list(1).size());
        assertEquals(recent.id, store.list(1).get(0).id);

        assertEquals(1, store.removeOlderThan(0));
        assertEquals(0, store.list(1).size());
    }

    @Test
    public void skipsDamagedFiles() throws Exception {
        ConversationStore store = new ConversationStore(folder.getRoot());
        store.save(conversation(1, "Good"), null);
        Files.write(new File(new File(folder.getRoot(), "1"), "00000000-0000-0000-0000-000000000000.json").toPath(), "not json".getBytes());

        assertEquals(1, store.list(1).size());
    }

    @Test
    public void markdownShowsQuestionsAnswersToolsAndActions() {
        Conversation c = new Conversation(1);
        c.record("question", "Context: channel ADT in\nWhy does message 12 fail?");
        c.record("tool", "oie_get_message {\"messageId\":12}");
        c.record("tool", "oie_get_channel {}");
        c.record("text", "PID-5 is empty, so the mapper fails.");
        c.record("action", "Script saved in channel 'ADT in'.");
        c.record("info", "Stopped.");

        String md = ConversationStore.markdown(c.title(), "OIE test", 0, c.log());

        assertTrue(md.startsWith("# Claude Assistant conversation\n"));
        assertTrue(md.contains("- **Server:** OIE test\n"));
        assertTrue(md.contains("- **Subject:** Context: channel ADT in Why does message 12 fail?\n"));
        assertTrue(md.contains("masked"));
        assertTrue(md.contains("\n## Question 1\n\nContext: channel ADT in\nWhy does message 12 fail?\n"));
        assertTrue(md.contains("\n- Tool: `oie_get_message {\"messageId\":12}`\n- Tool: `oie_get_channel {}`\n"));
        assertTrue(md.contains("\n### Claude\n\nPID-5 is empty, so the mapper fails.\n"));
        assertTrue(md.contains("\n**Action done:** Script saved in channel 'ADT in'.\n"));
        assertTrue(md.contains("\n_Stopped._\n"));
    }

    @Test
    public void questionForLogKeepsTheQuestionAndTheContext() {
        String sent = "[Server time: 2026-09-28 10:15 CEST]\n[Context from the Administrator: channel 'ADT in' (id 42)]\n\nWhy does it fail?";

        assertEquals("Context: channel 'ADT in' (id 42)\n\nWhy does it fail?", AssistantService.questionForLog(sent));
        assertEquals("Hello", AssistantService.questionForLog("[Server time: 2026-09-28 10:15 CEST]\n\nHello"));
    }

    @Test
    public void titleIsTheShortenedFirstQuestion() {
        Conversation c = new Conversation(1);
        assertEquals("(no question)", c.title());
        c.record("question", "A very long question about why the channel X Connections Central v5_2 Rest API keeps failing every night");
        assertEquals(78, c.title().length()); // 77 characters and the ellipsis
        assertTrue(c.title().endsWith("…"));
    }
}
