# Claude Assistant voor Open Integration Engine

Een extensie voor de OIE Administrator waarmee je vanuit de GUI met Claude praat over je server:
channels, berichten, fouten, scripts, code templates en serverstatus. Claude haalt de feiten zelf op
via dezelfde gereedschappen als de `oie-mcp-server`, en kan acties voorstellen die pas na jouw
bevestiging worden uitgevoerd.

## Waar vind je Claude in de Administrator?

| Plek | Taak | Context die meegaat |
|---|---|---|
| Linkermenu, **Other** | *Claude-assistent* | geen: vragen over de hele server |
| **Dashboard** (taak + rechtermuisknop) | *Vraag Claude* | geselecteerde channel(s)/connectors |
| **Message browser** (taak + rechtermuisknop) | *Vraag Claude* | geselecteerd bericht (channel, bericht-ID, connector) |
| **Channel-editor** (taak + rechtermuisknop) | *Vraag Claude* | channel die open staat (opgeslagen versie) |

Het chatvenster blijft open naast de Administrator. Ctrl+Enter verstuurt; *Nieuw gesprek* begint
opnieuw; *Stop* breekt een lopende vraag af.

## Wat Claude kan

**Lezen** (direct): serverinfo, channels met status en tellers, channelconfiguratie en scripts,
statistieken per connector, berichten zoeken en bekijken, events, serverlog, code templates en de
sleutels van de Configuration Map (nooit de waarden).

**Acties** (pas na klik op *Uitvoeren* in een bevestigingsvenster): channel deployen/undeployen,
starten/stoppen/pauzeren/hervatten, statistieken resetten, bericht opnieuw verwerken, bericht naar
een channel sturen en een JavaScript-script aanpassen (deploy/undeploy/pre/postprocessor, filterregel
of transformerstap). Een scriptwijziging slaat de channel op met een nieuwe revisie maar deployt niet;
als de channel intussen gewijzigd is, wordt er niets opgeslagen. Elke uitgevoerde actie komt als
event `Claude Assistant: …` in de audit log, op naam van de gebruiker die bevestigde.

## Privacy

Alles wat naar de Anthropic API gaat (jouw vragen, context, berichtinhoud, logs, foutmeldingen,
channelconfiguratie) gaat eerst door een masker, dezelfde regels als `mask.ts` in de oie-mcp-server:

- HL7 v2 PID-velden 2-7, 9, 11, 13, 14, 19 (ER7 en XML)
- GDT-patiëntvelden 3000-3107 (raw en XML van de GDT data type plugin)
- 9-cijferige nummers die de elfproef halen (BSN)
- eigen patronen via *Settings > Claude Assistant*

Dit is een vangnet, geen anonimisering. Stuur alleen echte patiëntdata door als daar een grondslag
en een verwerkersovereenkomst met Anthropic voor zijn. Gesprekken staan alleen in het geheugen van
de server (4 uur na laatste gebruik opgeruimd) en zijn alleen zichtbaar voor de gebruiker die ze startte.

## Rechten

De extensie voegt drie rechten toe (zichtbaar in de RBAC-extensie):

- **Use Claude Assistant**: chatten (alleen lezen)
- **Run Claude Assistant actions**: voorgestelde acties bevestigen
- **Manage Claude Assistant settings**: API-key, model en maskeerpatronen wijzigen

Gebruikers met channel-beperkingen kunnen de assistent niet gebruiken, omdat de tools alle channels zien.

## Instellen

1. Installeer `claude-assistant-<versie>.zip` via *Settings > Extensions > Install Extension* (of pak
   hem uit in `<OIE_HOME>/extensions/`) en herstart de OIE-service.
2. Ga naar *Settings > Claude Assistant*, vul de Anthropic API-key in en klik *Save*. De key wordt
   versleuteld opgeslagen en nooit naar de Administrator teruggestuurd.
3. Standaard: model `claude-opus-5`, effort `high`, maximaal 25 tool-aanroepen per vraag.

De OIE-server moet `https://api.anthropic.com` kunnen bereiken. Via een gateway of proxy? Zet dan
in `conf/custom.vmoptions` bijvoorbeeld `-Doie.claude.baseUrl=https://gateway.example/anthropic`.

## Bouwen

JDK 11+ en Maven; de engine-jars worden uit een OIE-installatie gehaald (`-Doie.home=...`, standaard
`C:/Program Files/OpenIntegrationEngine`).

```bash
mvn package
```

Resultaat: `target/claude-assistant-<versie>.zip`.

### Opbouw

- `shared` – de REST-interface (`/api/extensions/claude`); JSON als tekst, zodat er geen plugin-klassen
  door de XStream-serializer van OIE hoeven.
- `server` – serviceplugin, servlet, tools, masker, gesprekken en jobs. Draait op de classpath van OIE.
- `engine` – de lus met de Anthropic Java SDK. Staat met de SDK, Jackson 2.19, Kotlin en OkHttp in
  `lib/` en wordt in een eigen child-first class loader geladen: OIE levert Jackson 2.14, en de
  Kotlin-reflectie van de SDK werkt niet na relocatie (shading).
- `client` – Swing: chatvenster, taken in dashboard/message browser/channel-editor, instellingenscherm.

De client vraagt elke 0,8 s de voortgang op (`GET /jobs/{id}`), zodat lange antwoorden niet tegen de
HTTP-timeout van de Administrator lopen.
