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

## Mit Klarbudget verbinden

Geldmeister kann einen geprüften Bonbetrag lokal an **Klarbudget** im Ordner `C:\DIYsaster\ynab me` übergeben. Dafür in Klarbudget unter **Übersicht → Geldmeister** den Handyempfang starten. PC und Handy müssen im selben vertrauenswürdigen privaten WLAN sein. In der Android-APK die angezeigte PC-Adresse und Einmal-PIN eingeben, Konto und Kategorie wählen, Buchungsdatum und Empfänger prüfen und den Bon ausdrücklich senden.

Die Übergabe verwendet Klarbudgets lokale v1-API über HTTP im privaten LAN. PIN und sitzungsgebundenes Token schützen den Zugriff, verschlüsseln den Netzwerkverkehr aber nicht. Das Sitzungstoken bleibt nur im Arbeitsspeicher und wird beim Beenden der Verbindung verworfen. Die APK sendet nur Betrag in Euro-Cent, Konto- und Kategorie-ID, Datum, Empfänger, Notiz sowie eine zufällige Belegkennung. Foto und erkannter Bontext bleiben auf dem Handy. Vor dem Netzwerkaufruf fragt Android nativ nach einer zusätzlichen Bestätigung. Ein wiederholter Versand derselben Belegkennung erzeugt in Klarbudget keine zweite Buchung.

Die direkte LAN-Übergabe benötigt die Android-APK. In der installierbaren PWA im Browser steht nur der Bon-Scan zur Verfügung.

## Technik

Die App ist eine installierbare PWA ohne Build-Schritt. Sie verwendet Tesseract.js für OCR und bewertet erkannte Geldbeträge anhand typischer Gesamtbetrag-Bezeichnungen sowie ihrer Position auf dem Bon. Die Erkennung ist ein Vorschlag und sollte vor der Übernahme geprüft werden.

## Android-APK bauen

Das Android-Projekt verpackt dieselbe Oberfläche in eine native WebView-Hülle. Benötigt werden JDK 17 sowie Android SDK Platform 35 und Build Tools 35.0.0. Mit PowerShell lässt sich das debug-signierte APK so bauen:

```powershell
.\scripts\build-apk.ps1
```

Die Datei wird unter `dist\Geldmeister.apk` abgelegt. GitHub Actions baut außerdem bei jedem Push auf `start` ein APK-Artefakt. Dieses debug-signierte APK ist zum direkten Installieren auf Android gedacht, nicht für den Play Store.
