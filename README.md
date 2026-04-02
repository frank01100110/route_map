# Route Map

Applicazione Android per taxi routing con:

- mappa OpenStreetMap incorporata
- ricerca indirizzi con Nominatim
- percorsi stradali con OSRM
- tappe intermedie illimitate
- stima tariffa con base, km, minuti e supplementi

## Stack

- Kotlin
- Android SDK
- osmdroid per la mappa OSM
- OkHttp per le chiamate HTTP
- Nominatim per geocoding
- OSRM per routing

## Funzioni

- inserimento partenza
- aggiunta/rimozione tappe intermedie
- destinazione finale
- suggerimenti indirizzi mentre scrivi
- calcolo distanza e durata
- disegno del percorso sulla mappa
- calcolo tariffa taxi
- tariffa 2 automatica quando il percorso esce dal comune di Viareggio

## Build

Quando l'ambiente Android e pronto:

```bash
./gradlew assembleDebug
```

L'APK debug verra generato in:

`app/build/outputs/apk/debug/app-debug.apk`

In questo repository trovi anche una copia pronta della build debug:

`dist/route-map-debug.apk`

## Note operative

- L'app usa servizi pubblici Nominatim e OSRM: per produzione conviene avere endpoint dedicati o self-hosted.
- In questo repository e presente anche il prototipo Dash iniziale, utile come riferimento rapido lato desktop.
