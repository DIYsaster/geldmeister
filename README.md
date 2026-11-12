# Geldmeister

Eine mobile Web-App, die auf einem fotografierten Kassenbon die wahrscheinliche Gesamtsumme erkennt.

## Starten

Die App besteht aus statischen Dateien. Für Entwicklung kann sie mit einem lokalen Webserver geöffnet werden:

```powershell
py -m http.server 8000
```

Danach `http://localhost:8000` im Browser öffnen. Für die Installation auf dem Smartphone muss die App über HTTPS bereitgestellt werden, zum Beispiel mit GitHub Pages.

## Benutzung

1. „Kassenbon fotografieren“ oder „Aus Galerie wählen“ antippen.
2. „Summe erkennen“ starten und kurz warten, während die lokale OCR-Engine geladen wird.
3. Den erkannten Betrag prüfen, bei Bedarf anpassen und kopieren.

Die Bilder werden im Browser auf dem Gerät verarbeitet und nicht hochgeladen. Für die Texterkennung lädt die App Tesseract.js und das deutsche/englische Sprachmodell von jsDelivr. Zumindest beim ersten Scan wird daher eine Internetverbindung benötigt; das Foto wird dabei nicht übertragen.

## Technik

Die App ist eine installierbare PWA ohne Build-Schritt. Sie verwendet Tesseract.js für OCR und bewertet erkannte Geldbeträge anhand typischer Gesamtbetrag-Bezeichnungen sowie ihrer Position auf dem Bon. Die Erkennung ist ein Vorschlag und sollte vor der Übernahme geprüft werden.
