package com.leads.mail;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Step 6.2: every transactional template renders its HTML and text version with sample values, and escapes them. */
class MailTemplatesTest {

    private final MailRenderer renderer = new MailRenderer();

    private static Map<String, Object> sample(MailTemplate t) {
        Map<String, Object> vars = new HashMap<>();
        for (String name : t.variables) vars.put(name, name.equals("link") ? "https://app.example.test/x?token=abc" : "v-" + name);
        return vars;
    }

    @Test
    void allSevenTemplatesRenderHtmlAndText() {
        assertEquals(7, MailTemplate.values().length);
        for (MailTemplate t : MailTemplate.values()) {
            MailProvider.OutgoingMail m = renderer.render("someone@example.test", t, sample(t));
            assertEquals(t.subject, m.subject());
            assertTrue(m.html().contains("<html") && m.html().contains("WiseNquire"), t + " html");
            assertFalse(m.text().contains("<"), t + " text has no markup");
            for (Map.Entry<String, Object> v : sample(t).entrySet()) {
                assertTrue(m.text().contains(String.valueOf(v.getValue())), t + " text shows " + v.getKey());
                if (!v.getKey().equals("link")) assertTrue(m.html().contains(String.valueOf(v.getValue())), t + " html shows " + v.getKey());
            }
        }
    }

    @Test
    void valuesAreEscapedInHtml() {
        Map<String, Object> vars = new HashMap<>(sample(MailTemplate.INVITE));
        vars.put("inviterName", "<script>x</script>");
        String html = renderer.render("a@example.test", MailTemplate.INVITE, vars).html();
        assertFalse(html.contains("<script>x</script>"));
        assertTrue(html.contains("&lt;script&gt;"));
    }

    @Test
    void aMissingVariableIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> renderer.render("a@example.test", MailTemplate.OTP, Map.of("code", "1234")));
    }
}
