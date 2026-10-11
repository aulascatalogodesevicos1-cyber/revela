# Revela — editor de fotos estilo Lightroom com captura vinculada (Android)

App nativo em **Kotlin + Jetpack Compose** (Android 10+).

## O que já tem

**Hub inicial**
- Cartão de **Captura vinculada** com status da câmera em tempo real.
- Biblioteca em grade (fotos importadas e capturadas; bolinha laranja = veio da câmera).
- Importar JPEG, HEIF/HEIC, PNG e RAW (ARW, CR2, CR3, NEF, RAF, RW2, ORF, DNG…). Do RAW, o app usa a prévia JPEG embutida em resolução máxima e guarda o RAW junto.
- Segurar uma foto → apagar.

**Editor (não destrutivo, salva sozinho)**
- **Presets**: 8 prontos + os seus (salvar atual / segurar para apagar), com miniatura da própria foto.
- **Luz**: Exposição, Contraste, Realces, Sombras, Brancos, Pretos.
- **Cor**: Temperatura, Tonalidade, Vibração, Saturação, Preto e branco, Gradação de cor (sombras/realces).
- **Mistura (HSL)**: Matiz / Saturação / Luminância para 8 cores.
- **Curva**: RGB e canais R, G, B (arrastar, criar e remover pontos).
- **Efeitos**: Claridade, Desembaçar, Vinheta, Granulação.
- **Detalhe**: Nitidez, Redução de ruído.
- **Corte**: girar, espelhar, endireitar, proporções (1:1, 4:5, 3:2, 16:9…).
- Desfazer/refazer, segurar na foto para ver o original, copiar/colar ajustes, redefinir.
- Exportar JPEG para Galeria › Pictures/Revela (2048 px, 4096 px ou máxima).

**Captura vinculada**
- **Cabo USB (PTP)** — cliente PTP próprio (transferências bulk), não depende do MtpDevice:
  - **Sony em "PC Remoto"**: handshake SDIO da Sony, **disparo pelo app** (meio-clique + disparo) e recebimento automático de cada foto, inclusive as tiradas no botão da câmera.
  - **Outras marcas / Sony em MTP**: detecta fotos novas no cartão (evento ObjectAdded + varredura) e baixa sozinho. Disparo pelo app quando a câmera aceita o comando PTP padrão.
  - Ao plugar a câmera, o app abre direto na tela de captura.
- **Wi-Fi Sony** — Camera Remote API (descoberta SSDP + JSON-RPC), disparo pelo app e recebimento das fotos tiradas na câmera.
- **Preset na captura**: escolha um preset (ou "Nenhum") e cada foto já chega com ele aplicado.
- RAW + JPEG da mesma foto viram uma única foto na biblioteca.
- Tela fica acesa durante a sessão; última foto em destaque + tira com as capturas da sessão.

## Como compilar

1. Abra a pasta `Revela` no **Android Studio** (Ladybug ou mais novo) e aguarde o *Gradle Sync*.
   - Para compilar pelo terminal, gere o wrapper uma vez: `gradle wrapper --gradle-version 8.11.1`, depois `./gradlew assembleDebug`.
2. Rode no celular (Run ▶) ou instale `app/build/outputs/apk/debug/app-debug.apk`.

Versões: AGP 8.7.3 · Kotlin 2.0.21 · Compose BOM 2024.12 · compileSdk 35 · minSdk 29.

## Configurar a câmera Sony

**Sony ZV-E10 — cabo USB (é o caminho para este modelo)**
1. *MENU › Rede › Conexão Smartphone › Conexão Smartphone* → **Desl.** (precisa estar desligado antes).
2. *MENU › Rede › Função PC Remoto › PC Remoto* → **Lig.**
3. *Método Conexão PC Remoto* → **USB**.
4. *Destino salv. imagem* → **Só PC** (ou **PC+Câmera** para guardar cópia no cartão). Com RAW+JPEG, use *Img. salva PC RAW+J* para escolher o que vai para o celular — só JPEG é o mais rápido.
5. Ligue no celular com cabo **USB-C ↔ USB-C** e aceite a permissão USB. O app abre direto na captura vinculada.

Outras Alpha/RX/ZV seguem o mesmo princípio (*Conexão USB = PC Remoto* nos menus antigos).

**Wi-Fi** (modelos com "Smart Remote"/"Ctrl c/ Smartphone": a6000, a6300, a6500, a7 II, a7 III, a7R III, a9, RX100 IV–VII…)
1. Na câmera abra **Ctrl c/ Smartphone** / **Smart Remote Embedded**.
2. Conecte o celular à rede **DIRECT-xxxx** da câmera (o Android pode avisar "sem internet" — mantenha conectado).
3. No app: Captura vinculada › **Wi-Fi Sony**.

Modelos mais novos (ZV-E10, a7 IV, a7C II, a7R V, a6700, ZV-E1…) não têm a Camera Remote API por Wi-Fi — use o cabo USB.

## Estrutura

```
app/src/main/java/com/revela/app/
├── model/        EditParams (todos os ajustes) e presets embutidos
├── imaging/      ImageProcessor (motor de revelação), curvas, geometria, leitura de RAW, exportação
├── data/         PhotoRepository (biblioteca, ajustes .json, presets, cache)
├── tether/
│   ├── usb/      Transporte PTP USB, câmera Sony PC Remoto / PTP genérico, permissão USB
│   ├── wifi/     Sony Camera Remote API
│   └── TetherManager (sessão, preset automático)
└── ui/           Hub, Captura vinculada, Editor (Compose)
```

## Limitações conhecidas / próximos passos

- O revelador trabalha sobre a prévia JPEG do RAW (não decodifica o sensor). Revelação RAW real exigiria uma biblioteca nativa como LibRaw via NDK.
- O processamento é em CPU (multithread). Para prévias em tempo real em fotos muito grandes, um próximo passo é portar o pipeline para GPU (AGSL `RuntimeShader` no Android 13+ ou OpenGL ES).
- Disparo remoto por USB de Canon/Nikon usa o comando PTP genérico; os comandos próprios dessas marcas (EOS/Nikon remote release) podem ser adicionados em `UsbPtpCamera`.
- Ainda não há ajustes locais (pincel, gradiente, máscaras), histograma e exportação com metadados EXIF.
- Este código não foi compilado no ambiente em que foi gerado; ao abrir no Android Studio, eventuais avisos de compilação devem ser pontuais.
