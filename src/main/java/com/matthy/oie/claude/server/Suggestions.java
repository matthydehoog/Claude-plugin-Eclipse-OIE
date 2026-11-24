/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import java.util.List;
import java.util.Map;

/**
 * Suggested questions for a chat opened with context: fixed per kind of context (no model call),
 * in the fixed response language, or English when the language is automatic. The kind follows
 * from how the clients start the context text (ClaudeClientPlugin and webadmin/web/plugin.js).
 */
final class Suggestions {

    static final String CHANNEL = "channel", MESSAGE = "message", CODE_TEMPLATE = "codeTemplate", GLOBAL_SCRIPTS = "globalScripts", DASHBOARD = "dashboard";

    private Suggestions() {
    }

    /** The kind of context, or null for no context or one without suggestions. */
    static String kind(String context) {
        if (context == null) {
            return null;
        }
        String c = context.trim();
        if (c.startsWith("Channel open in the editor") || c.startsWith("Selected channel")) {
            return CHANNEL;
        }
        if (c.startsWith("Selected message")) {
            return MESSAGE;
        }
        if (c.startsWith("Selected code template") || c.startsWith("Code template open")) {
            return CODE_TEMPLATE;
        }
        if (c.startsWith("Global Scripts")) {
            return GLOBAL_SCRIPTS;
        }
        if (c.startsWith("Selected in the dashboard")) {
            return DASHBOARD;
        }
        return null;
    }

    /** The suggestions for a context in a response language (a name from the settings); empty when there are none. */
    static List<String> forContext(String context, String language) {
        String kind = kind(context);
        if (kind == null) {
            return List.of();
        }
        Map<String, List<String>> texts = TEXTS.getOrDefault(language == null ? "" : language.trim(), TEXTS.get("English"));
        return texts.get(kind);
    }

    private static Map<String, List<String>> of(List<String> channel, List<String> message, List<String> codeTemplate, List<String> globalScripts, List<String> dashboard) {
        return Map.of(CHANNEL, channel, MESSAGE, message, CODE_TEMPLATE, codeTemplate, GLOBAL_SCRIPTS, globalScripts, DASHBOARD, dashboard);
    }

    /** By the language names of the Response language setting. */
    static final Map<String, Map<String, List<String>>> TEXTS = Map.ofEntries(
            Map.entry("English", of(
                    List.of("Explain what this channel does.", "Check this channel for errors and weak spots.", "Document this channel."),
                    List.of("Why did this message fail?", "Explain how this message was processed.", "Which destinations received this message?", "Show the structure of this message."),
                    List.of("Explain this code template.", "Where is this code template used?"),
                    List.of("Review the global scripts.", "Explain what the global scripts do."),
                    List.of("Analyze the status and errors of the selection.", "Are messages queued or stuck here?"))),
            Map.entry("Dutch", of(
                    List.of("Leg uit wat dit kanaal doet.", "Controleer dit kanaal op fouten en zwakke plekken.", "Documenteer dit kanaal."),
                    List.of("Waarom is dit bericht mislukt?", "Leg uit hoe dit bericht is verwerkt.", "Welke destinations hebben dit bericht ontvangen?", "Toon de structuur van dit bericht."),
                    List.of("Leg deze code template uit.", "Waar wordt deze code template gebruikt?"),
                    List.of("Beoordeel de global scripts.", "Leg uit wat de global scripts doen."),
                    List.of("Analyseer de status en fouten van de selectie.", "Staan hier berichten in de wachtrij of vast?"))),
            Map.entry("German", of(
                    List.of("Erkläre, was dieser Kanal macht.", "Prüfe diesen Kanal auf Fehler und Schwachstellen.", "Dokumentiere diesen Kanal."),
                    List.of("Warum ist diese Nachricht fehlgeschlagen?", "Erkläre, wie diese Nachricht verarbeitet wurde.", "Welche Destinations haben diese Nachricht erhalten?", "Zeige die Struktur dieser Nachricht."),
                    List.of("Erkläre dieses Code Template.", "Wo wird dieses Code Template verwendet?"),
                    List.of("Prüfe die globalen Skripte.", "Erkläre, was die globalen Skripte tun."),
                    List.of("Analysiere Status und Fehler der Auswahl.", "Gibt es hier wartende oder hängende Nachrichten?"))),
            Map.entry("French", of(
                    List.of("Explique ce que fait ce canal.", "Vérifie ce canal : erreurs et points faibles.", "Documente ce canal."),
                    List.of("Pourquoi ce message a-t-il échoué ?", "Explique comment ce message a été traité.", "Quelles destinations ont reçu ce message ?", "Montre la structure de ce message."),
                    List.of("Explique ce code template.", "Où ce code template est-il utilisé ?"),
                    List.of("Passe en revue les scripts globaux.", "Explique ce que font les scripts globaux."),
                    List.of("Analyse l'état et les erreurs de la sélection.", "Des messages sont-ils en file d'attente ou bloqués ici ?"))),
            Map.entry("Spanish", of(
                    List.of("Explica qué hace este canal.", "Revisa este canal en busca de errores y puntos débiles.", "Documenta este canal."),
                    List.of("¿Por qué falló este mensaje?", "Explica cómo se procesó este mensaje.", "¿Qué destinos recibieron este mensaje?", "Muestra la estructura de este mensaje."),
                    List.of("Explica esta plantilla de código.", "¿Dónde se usa esta plantilla de código?"),
                    List.of("Revisa los scripts globales.", "Explica qué hacen los scripts globales."),
                    List.of("Analiza el estado y los errores de la selección.", "¿Hay mensajes en cola o atascados aquí?"))),
            Map.entry("Italian", of(
                    List.of("Spiega cosa fa questo canale.", "Controlla questo canale per errori e punti deboli.", "Documenta questo canale."),
                    List.of("Perché questo messaggio è fallito?", "Spiega come è stato elaborato questo messaggio.", "Quali destinazioni hanno ricevuto questo messaggio?", "Mostra la struttura di questo messaggio."),
                    List.of("Spiega questo code template.", "Dove viene usato questo code template?"),
                    List.of("Rivedi gli script globali.", "Spiega cosa fanno gli script globali."),
                    List.of("Analizza lo stato e gli errori della selezione.", "Ci sono messaggi in coda o bloccati qui?"))),
            Map.entry("Portuguese", of(
                    List.of("Explica o que este canal faz.", "Verifica este canal quanto a erros e pontos fracos.", "Documenta este canal."),
                    List.of("Porque falhou esta mensagem?", "Explica como esta mensagem foi processada.", "Que destinos receberam esta mensagem?", "Mostra a estrutura desta mensagem."),
                    List.of("Explica este code template.", "Onde é usado este code template?"),
                    List.of("Revê os scripts globais.", "Explica o que fazem os scripts globais."),
                    List.of("Analisa o estado e os erros da seleção.", "Há mensagens em fila ou bloqueadas aqui?"))),
            Map.entry("Polish", of(
                    List.of("Wyjaśnij, co robi ten kanał.", "Sprawdź ten kanał pod kątem błędów i słabych punktów.", "Udokumentuj ten kanał."),
                    List.of("Dlaczego ta wiadomość zakończyła się błędem?", "Wyjaśnij, jak ta wiadomość została przetworzona.", "Które miejsca docelowe otrzymały tę wiadomość?", "Pokaż strukturę tej wiadomości."),
                    List.of("Wyjaśnij ten szablon kodu.", "Gdzie jest używany ten szablon kodu?"),
                    List.of("Przejrzyj skrypty globalne.", "Wyjaśnij, co robią skrypty globalne."),
                    List.of("Przeanalizuj status i błędy zaznaczonych elementów.", "Czy są tu wiadomości w kolejce lub zablokowane?"))),
            Map.entry("Swedish", of(
                    List.of("Förklara vad den här kanalen gör.", "Granska den här kanalen efter fel och svaga punkter.", "Dokumentera den här kanalen."),
                    List.of("Varför misslyckades det här meddelandet?", "Förklara hur det här meddelandet bearbetades.", "Vilka destinationer tog emot det här meddelandet?", "Visa strukturen för det här meddelandet."),
                    List.of("Förklara den här kodmallen.", "Var används den här kodmallen?"),
                    List.of("Granska de globala skripten.", "Förklara vad de globala skripten gör."),
                    List.of("Analysera status och fel för urvalet.", "Finns det köade eller fastnade meddelanden här?"))),
            Map.entry("Danish", of(
                    List.of("Forklar, hvad denne kanal gør.", "Gennemgå denne kanal for fejl og svage punkter.", "Dokumentér denne kanal."),
                    List.of("Hvorfor fejlede denne besked?", "Forklar, hvordan denne besked blev behandlet.", "Hvilke destinationer modtog denne besked?", "Vis strukturen af denne besked."),
                    List.of("Forklar denne kodeskabelon.", "Hvor bruges denne kodeskabelon?"),
                    List.of("Gennemgå de globale scripts.", "Forklar, hvad de globale scripts gør."),
                    List.of("Analysér status og fejl for udvalget.", "Er der beskeder i kø eller fastlåste beskeder her?"))),
            Map.entry("Norwegian", of(
                    List.of("Forklar hva denne kanalen gjør.", "Gå gjennom denne kanalen for feil og svake punkter.", "Dokumenter denne kanalen."),
                    List.of("Hvorfor feilet denne meldingen?", "Forklar hvordan denne meldingen ble behandlet.", "Hvilke destinasjoner mottok denne meldingen?", "Vis strukturen til denne meldingen."),
                    List.of("Forklar denne kodemalen.", "Hvor brukes denne kodemalen?"),
                    List.of("Gå gjennom de globale skriptene.", "Forklar hva de globale skriptene gjør."),
                    List.of("Analyser status og feil for utvalget.", "Er det meldinger i kø eller som står fast her?"))),
            Map.entry("Finnish", of(
                    List.of("Selitä, mitä tämä kanava tekee.", "Tarkista tämä kanava virheiden ja heikkouksien varalta.", "Dokumentoi tämä kanava."),
                    List.of("Miksi tämän viestin käsittely epäonnistui?", "Selitä, miten tämä viesti käsiteltiin.", "Mitkä kohteet vastaanottivat tämän viestin?", "Näytä tämän viestin rakenne."),
                    List.of("Selitä tämä koodipohja.", "Missä tätä koodipohjaa käytetään?"),
                    List.of("Tarkista globaalit skriptit.", "Selitä, mitä globaalit skriptit tekevät."),
                    List.of("Analysoi valinnan tila ja virheet.", "Onko täällä jonossa olevia tai jumiutuneita viestejä?"))));
}
