package com.example.walking_meditation.audio;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.io.StringReader;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;
import org.w3c.dom.Document;
import static org.junit.Assert.*;

public class TtsPayloadTest {
    @Test public void qwenRetainsContractAndHandlesPauseCaseInsensitively() throws Exception {
        JSONObject json = new QwenPayload().toJson(" One.(PAUSE)Two. ");
        assertEquals("One.\n\nTwo.", json.getString("text"));
        assertEquals("English", json.getString("language"));
        assertEquals("Ryan", json.getString("speaker"));
        assertEquals("Calm and clear", json.getString("instruct"));
        assertEquals("192k", json.getString("bitrate"));
        assertThrows(IOException.class, () -> new QwenPayload().toJson("(pause)"));
        assertThrows(IOException.class, () -> new QwenPayload().toJson("a".repeat(5001)));
    }

    @Test public void pollyEscapesNarrationAndInsertsOnlyItsOwnSsmlTags() throws Exception {
        JSONObject json = new PollyPayload("Joanna", "en-US", "standard", 2500)
                .toJson("(pause)A & B <tag> \"quote\" 'apostrophe'(PAUSE)(pause)End.(pause)");
        assertEquals("ssml", json.getString("TextType"));
        assertEquals("mp3", json.getString("OutputFormat"));
        assertEquals("Joanna", json.getString("VoiceId"));
        assertEquals("en-US", json.getString("LanguageCode"));
        assertEquals("standard", json.getString("Engine"));
        assertFalse(json.has("speaker"));
        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new InputSource(new StringReader(json.getString("Text"))));
        assertEquals("speak", document.getDocumentElement().getTagName());
        assertEquals(4, document.getElementsByTagName("break").getLength());
        assertEquals("2500ms", document.getElementsByTagName("break").item(0)
                .getAttributes().getNamedItem("time").getNodeValue());
        assertEquals(0, document.getElementsByTagName("tag").getLength());
        assertEquals("A & B <tag> \"quote\" 'apostrophe'End.", document.getDocumentElement().getTextContent());
    }

    @Test public void pollyValidatesItsOwnLimitsAndPauseDuration() throws Exception {
        assertThrows(IOException.class, () -> new PollyPayload().toJson("(pause)"));
        assertThrows(IOException.class, () -> new PollyPayload().toJson("a".repeat(3001)));
        assertThrows(IOException.class, () -> new PollyPayload().toJson("&".repeat(1300)));
        assertThrows(IllegalArgumentException.class, () -> new PollyPayload("Joanna", "en-US", "standard", 10001));
        assertEquals("<speak>Walk.</speak>", new PollyPayload().toJson("Walk.").getString("Text"));
    }
}
