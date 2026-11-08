# ReelSaver

Salvează în galerie reel-urile de pe Facebook (Movies/ReelSaver).

## Compilare
1. Android Studio → Open → folderul `ReelSaver`.
2. Lasă-l să facă Gradle sync (descarcă Gradle 8.11.1 și AGP 8.7.3).
3. Run pe telefon, sau Build → Build APK(s).

Dacă Android Studio cere wrapper: Terminal → `gradle wrapper` (sau acceptă propunerea IDE-ului).

## Utilizare
1. Prima dată: deschide aplicația, apasă „Facebook (login)” și loghează-te în WebView
   (cookie-urile rămân salvate, deci o faci o singură dată).
2. În Facebook: pe reel → Share → Mai multe / Copiază link → alege **ReelSaver**
   (sau lipește link-ul în câmpul de sus).
3. Așteaptă „Video găsit: HD/SD”, apoi **Salvează în galerie**.

## Cum funcționează
- Facebook trimite de obicei video și audio separat (DASH). Aplicația prinde ambele fluxuri din
  traficul WebView-ului, le descarcă și le unește local cu MediaMuxer (fără re-encodare).
- `VideoExtractor` caută și URL-uri progresive (video+audio) în pagină:
  `browser_native_hd_url`, `playable_url_quality_hd`, `browser_native_sd_url`, `playable_url`…,
  plus `src`-ul elementelor `<video>`. Pagina e re-scanată la 2 s, 60 s în total.
- `VideoSaver` descarcă cu cookie-urile WebView-ului și scrie prin MediaStore (minSdk 29,
  fără permisiuni de stocare).
- Dacă o descărcare eșuează, URL-ul e scos din listă și următoarea apăsare încearcă alt URL.

Facebook își schimbă des structura paginilor; dacă nu mai găsește videoclipul,
cheile de căutat sunt în `VideoExtractor.KEYS`.
