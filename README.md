# VR Phone Bridge  (beta 0.1)

Um app Android só, no lugar do **Trinus VR + IP Webcam**:

```
CELULAR (app)                                   PC
 ARCore ── pose da cabeça (6DOF) ──UDP 6000──►  relay.py ──► OpenTrack UDP :4242 ──► VRto3D (headset do SteamVR)
 câmera ─── MJPEG http://CEL:8080/video ──────► hands.py (igual ao IP Webcam)       └► VMT tracker "cabeça" (6DOF, experimental)
 tela  ◄── H.264 (MPEG-TS) UDP 5000 ◄───────── ffmpeg (iniciado pelo relay.py --stream) captura a tela do VRto3D (lado a lado)
```

## O que foi testado e o que NÃO foi (leia isto primeiro)
- **Testado:** o `relay.py` (recentralizar, yaw/pitch, formato OpenTrack e OSC do VMT) com um celular simulado (`fake_phone.py`).
- **NÃO testado:** o app Android (nunca foi compilado nem rodou num celular), o ffmpeg → celular, o VRto3D recebendo o OpenTrack e o 6DOF via VMT.
  Pode ter erro de compilação ou de execução. A ideia é você me mandar o erro/print e a gente corrige.
- **Limitações desta beta:** sem correção de lente do Cardboard (a imagem é lado a lado "reta"), latência provavelmente alta (centenas de ms),
  e o ARCore entrega a pose a ~30 Hz (ainda sem mistura com giroscópio/previsão).

## 1) Gerar o APK (sem instalar nada: GitHub Actions)
1. Crie um repositório no GitHub e suba **todo o conteúdo desta pasta** (inclusive a pasta oculta `.github`).
2. Aba **Actions → Build APK → Run workflow**. Em ~5 min aparece o artefato **VRPhoneBridge-apk** (zip com `app-debug.apk`).
3. Se o build falhar, abra o log do passo "Build debug APK" e me mande o erro.

Alternativa: **Android Studio** → *Open* na pasta `android/` → esperar o sync → *Build > Build APK(s)*.

## 2) Instalar no celular
- Copie o `app-debug.apk`, abra e permita "instalar de fontes desconhecidas".
- O celular precisa ter **ARCore** ("Serviços do Google Play para RA") e suporte a ele. O app pede a instalação se faltar.
- **Feche o IP Webcam / Iriun / qualquer app de câmera**: o ARCore usa a câmera sozinho. Este app já serve a câmera pro `hands.py`.

## 3) PC (uma vez só)
1. **Python 3** (o `relay.py` não precisa instalar pacotes).
2. **ffmpeg** no PATH (https://www.gyan.dev/ffmpeg/builds/ → "release essentials").
3. **VRto3D** (driver de headset do SteamVR): https://github.com/oneup03/VRto3D
   - Saída em **Side-by-Side** e janela no monitor principal (siga o guia deles).
   - No OSD (`Ctrl + Home`) → aba *Tracking* → marque **Enable OpenTrack**, porta **4242**, reinicie o SteamVR.
4. **VMT** (você já usa com o hands.py).
5. Firewall do Windows: permita o Python na rede privada (UDP 6000).

## 4) Usar
1. PC: `python relay.py --stream` (ou duplo clique em `start_relay.bat`). GPU NVIDIA? `--encoder h264_nvenc`. PC fraco? `--size 960x540 --bitrate 5M --fps 30`.
2. Abra o SteamVR (com o VRto3D).
3. Celular (mesmo Wi-Fi): abra o app, digite o **IP do PC**, toque **Iniciar** e aceite a câmera. Mova o celular devagar até aparecer "6DOF OK".
4. Coloque no Cardboard (câmera traseira pra frente). No PC, tecle **r** na janela do relay para recentralizar olhando pra frente.
5. Mãos: `python hands.py http://IP_DO_CELULAR:8080/video` (o relay mostra o comando pronto assim que acha o celular).
   O app mostra o FOV da câmera na tela: coloque esse valor em `CAM_FOV_H` do hands.py.
   Se a imagem vier de cabeça pra baixo, use a tecla de rotação do hands.py.

## 5) 6DOF de verdade (posição da cabeça)
O OpenTrack no VRto3D é **3DoF** (só rotação), segundo a documentação deles. A posição vai pelo **VMT**: o relay cria um tracker (slot 2) com a pose completa da cabeça.
O VRto3D sabe seguir um tracker marcado como cabeça (documentado para o Vive Tracker). **Experimental, não testado com o VMT**: em `Steam\config\steamvr.vrsettings`:
```
"trackers" : { "/devices/vmt/VMT_2" : "TrackerRole_Camera" },
"TrackingOverrides" : { "/devices/vmt/VMT_2" : "/user/head" }
```
Se o nome do dispositivo for outro, ele aparece no SteamVR (menu → dispositivos). Se funcionar, desative `Enable OpenTrack` no VRto3D para não brigar.

## Ajustes do relay.py
- Eixo invertido: `--invert yaw` (ou `pitch`, `roll`, `x`, `y`, `z`, separados por vírgula).
- Altura da cabeça no VMT: `--height 1.6`.
- Testar sem celular: `python fake_phone.py` (simula uma cabeça girando).

## Próximos passos (depois de rodar)
decodificador de baixa latência (MediaCodec direto), correção de lente Cardboard (shader), mistura ARCore + giroscópio + previsão, recentralizar por gesto, pareamento automático sem digitar IP.
