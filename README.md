# VR Phone Bridge — beta 0.4

App Android + relay para usar o celular como HMD no SteamVR, mantendo o rastreamento de mãos pelo `hands.py`.

## Arquitetura atual

```text
CELULAR                                                   PC
ARCore 6DOF ── UDP 6000 ────────────────────────────────► relay.py
                                                           └─ OpenTrack 6DOF :4242 ─► VRto3D (HMD OpenVR real)
Câmera ── HTTP MJPEG :8080/video ────────────────────────► hands.py ─► VMT (mãos L/R)
Tela VRto3D ◄── H.264 bruto (Annex-B) UDP :5000 ◄───────── FFmpeg
```

- **VRto3D é o driver HMD do SteamVR**. O relay configura `forcedDriver: vrto3d`, ativa `requireHmd` e remove a configuração antiga do `driver_null`.
- A pose 6DOF do ARCore é enviada ao VRto3D pelo protocolo OpenTrack, na porta UDP 4242. Não usamos mais o tracker VMT como cabeça.
- O `hands.py` continua separado e envia os controladores esquerdo/direito pelo VMT, slots 0 e 1.
- O VRto3D gera a imagem estéreo real: cada olho é renderizado pelo jogo/driver com sua própria perspectiva. A saída é configurada como **Side-by-Side (SbS)** e o Android mostra cada metade na lente correspondente.
- O app Android continua fornecendo a câmera por MJPEG para o `hands.py`, recebendo o vídeo H.264 e aplicando correção de lente/timewarp.

**Importante sobre estéreo:** o celular não consegue criar profundidade real a partir de uma imagem 2D única. O jogo precisa estar usando renderização estéreo/VR ou um perfil/mod compatível com o VRto3D. O VRto3D produz os dois olhos; o Android apenas os distribui às lentes.

## 1. Instalar o driver HMD

1. Instale o SteamVR.
2. Instale o [VRto3D (releases oficiais)](https://github.com/oneup03/VRto3D/releases).
3. Abra o SteamVR uma vez para o VRto3D criar a configuração padrão.
4. Feche o SteamVR completamente antes da primeira execução do relay.

O VRto3D é um driver OpenVR que registra o HMD no SteamVR e renderiza modos estéreo nativos, incluindo SbS. O relay seleciona esse driver automaticamente e configura `output_mode: SbS`, `use_open_track: true` e `open_track_port: 4242`.

## 2. Gerar o APK

O workflow `.github/workflows/android.yml` agora dispara em `main`, `master` e `beta`, além de permitir execução manual.

1. Envie o conteúdo do projeto para a branch `beta`.
2. No GitHub, abra **Actions → Build APK**.
3. Baixe o artefato `VRPhoneBridge-apk` quando o build terminar.

O APK usa `versionName 0.4-beta`. A branch `main` não precisa ser alterada.

## 3. Preparar o PC

- Python 3.
- FFmpeg no `PATH` (build com `gdigrab` e, se disponível, `ddagrab`).
- Para o rastreamento de mãos: instale as dependências com `pip install -r pc/requirements.txt` (`opencv-python`, `mediapipe`, `numpy` e `openvr`).
- Mantenha `relay.py`, `hands__66_ (2).py` e `hand_landmarker.task` juntos na pasta `pc/`.

O `relay.py` escolhe automaticamente um encoder H.264 que passe no teste. Em PCs sem encoder de hardware compatível, usa `libx264` com preset de baixa latência.

## 4. Usar

1. Feche o SteamVR na primeira execução.
2. No PC, execute `start_relay.bat`.
3. O relay salva um backup de `steamvr.vrsettings`, seleciona o driver `vrto3d`, ativa OpenTrack 6DOF e configura a saída SbS.
4. O SteamVR será iniciado pelo relay. Se o VRto3D pedir uma configuração inicial, confirme que o modo de saída está em **SbS**.
5. No celular, abra o app, digite o IP do PC e toque em **Iniciar**.
6. Quando o celular conectar, o relay inicia o stream e abre o `hands.py` automaticamente.
7. Pressione `r` na janela do relay para recentralizar; `q` encerra.

O servidor MJPEG do celular fica em `http://IP_DO_CELULAR:8080/video`, compatível com o `hands.py`.

### Ajustes úteis

- `python relay.py --stream --fps 30 --size 960x540 --bitrate 5M` — perfil mais leve para PC fraco.
- `python relay.py --test-video` — testa o vídeo no celular sem depender do jogo.
- `python relay.py --no-hands` — não inicia o rastreamento de mãos.
- `python relay.py --manual --stream` — não altera nem inicia o SteamVR automaticamente.
- `python relay.py --undo-head` — restaura o backup de `steamvr.vrsettings` e da configuração do VRto3D.

## 5. O que foi alterado nesta revisão

- O relay mais novo do RAR substitui a versão antiga do ZIP (mantém previsão de pose, detecção de encoder, captura e inicialização automática do `hands.py`).
- O Null Driver/falso HMD foi removido da configuração automática.
- O SteamVR passa a selecionar o driver HMD VRto3D e a receber a pose 6DOF pelo OpenTrack.
- O VMT deixa de ser usado como HMD; o `hands.py` continua usando VMT para as duas mãos.
- A saída do VRto3D é forçada para SbS para combinar com o renderer estéreo do Android.
- O GitHub Actions também compila quando há push na branch `beta`.

## Limitações e testes

O ambiente de edição não tem Android SDK/Gradle nem SteamVR/Windows, então o APK e a inicialização real do driver ainda precisam ser confirmados pelo GitHub Actions e no seu PC. O script Python passou na verificação sintática e os testes simulados de configuração do VRto3D, saída SbS, OpenTrack e restauração do backup.
