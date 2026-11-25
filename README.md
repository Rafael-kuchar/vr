# VR Phone Bridge — beta 0.5 ALVR integration

Esta revisão troca a arquitetura antiga baseada em VRto3D + captura do desktop pela base PhoneVR/ALVR. O SteamVR deve carregar o driver HMD do ALVR diretamente; o aplicativo Android usa o cliente PhoneVR/ALVR no modo `noGvr`, com uma ponte ARCore para pose 6DOF e um pequeno servidor MJPEG para o rastreador de mãos existente.

**Isto é uma build de integração para teste, não uma versão já validada no Moto G60/SteamVR.** O ambiente desta edição não tem Android SDK/NDK, Windows, SteamVR nem GT 710 para executar o teste físico. O GitHub Actions precisa confirmar primeiro que o APK compila.

## O que muda nesta arquitetura

```text
Moto G60                                               PC Windows
ARCore 6DOF ── JNI ──► cliente PhoneVR/ALVR ── Wi-Fi ─► ALVR Server
Camera YUV ── JPEG ──► MJPEG :8080/video ─────────────► hands__66_ (2).py
                                                               └─ VMT ─► mãos L/R

Jogo SteamVR ──► driver OpenVR do ALVR ──► vídeo estéreo ──► cliente Android ALVR
```

- O VRto3D e o relay antigo que capturava o desktop deixam de fazer parte do caminho.
- O driver HMD é o do ALVR Server; ele registra um HMD no SteamVR. O nome/ícone do dispositivo não é o de um Quest físico.
- O cliente Android parte do projeto PhoneVR, usa o ALVR `noGvr` e mantém renderização Cardboard com duas texturas/olhos.
- A pose ARCore é enviada diretamente ao núcleo nativo do cliente ALVR. Se a permissão/câmera/ARCore falhar, o cliente mantém o tracker Cardboard como fallback de orientação.
- O app publica a câmera em `http://IP_DO_CELULAR:8080/video` para o script MediaPipe/VMT atual.
- O `hands__66_ (2).py` continua sendo o programa de tracking de mãos no PC.

## Versão do ALVR

A documentação do PhoneVR informa suporte ao **ALVR Server v20.8.0** para esta linha do cliente. Use essa versão para o primeiro teste; não misture com uma versão mais nova do ALVR até confirmar compatibilidade. Veja o README upstream: https://github.com/PhoneVR-Developers/PhoneVR

## Gerar o APK pelo GitHub Actions

1. Extraia este ZIP.
2. Na branch `beta`, substitua o arquivo `.github/workflows/android.yml` pelo workflow deste pacote. Ele é diferente do workflow simples da versão 0.4: agora precisa baixar e compilar o cliente nativo ALVR/PhoneVR.
3. Envie as pastas `android/phonevr-overlay`, `pc` e `tools`, além do `README.md`, diretamente para a raiz da branch `beta`. Não envie uma pasta externa envolvendo tudo.
4. Remova da branch `beta` os lançadores antigos `pc/start_relay.bat` e `pc/fake_phone.py` e o arquivo `pc/relay.py`, para não iniciar acidentalmente a antiga captura de desktop. O novo pacote não usa esses arquivos.
5. Confira que `.github/workflows/android.yml` aparece na raiz do repositório dentro de `.github/workflows/` e faça **Commit changes** diretamente na `beta`.
6. Abra **Actions → Build VR Phone Bridge (ALVR)**.
7. Quando terminar com sucesso, baixe o artefato `VRPhoneBridge-ALVR-beta`. Ele contém `VRPhoneBridge-ALVR.apk` e `VRPhoneBridge-PC-hands.zip`.

A workflow roda somente quando há commit na branch `beta` (ou quando iniciada manualmente nessa branch). Ela faz checkout do código PhoneVR e dos submódulos ALVR; executa `prepare-alvr-deps.sh nogvr`; aplica os patches ARCore/MJPEG; compila `assembleNoGvrDebug` e publica os artefatos. Ela não altera a branch `main`.

## Primeiro teste no PC

1. Instale/abra SteamVR e ALVR Server v20.8.0 no PC.
2. No ALVR Dashboard, conclua a configuração inicial do streamer e mantenha o servidor ativo.
3. No primeiro APK, abra VR Phone Bridge e escolha o modo **ALVR** quando a tela de seleção aparecer.
4. Permita o acesso à câmera. O ARCore precisa da câmera para rastreamento 6DOF; o cliente ainda pode usar o tracker Cardboard se o ARCore não iniciar.
5. Coloque PC e celular na mesma rede Wi-Fi. Abra `http://IP_DO_CELULAR:8080/` no navegador do PC; a página de status deve responder. `http://IP_DO_CELULAR:8080/shot.jpg` deve mostrar uma imagem quando o ARCore estiver ativo.
6. Descompacte `VRPhoneBridge-PC-hands.zip`, instale as dependências de `requirements.txt` se ainda não estiverem instaladas e execute `start_hands.bat`. Digite o IP do celular.

## Perfil de desempenho inicial

A meta é **aproximar de 24 FPS**, não prometer 24 FPS. A GT 710 não deve ser presumida como compatível com NVENC moderno. No ALVR Dashboard, comece com resolução/escala baixa (por exemplo, 50% ou menos), H.264 e encoder por software (x264) quando o ALVR oferecer essa opção. Reduza ainda mais a resolução/bitrate se o i3-9100F não sustentar a taxa. Evite AV1/HEVC no primeiro teste; use H.264.

O script de mãos usa um stream de câmera reduzido e limitado a cerca de 8 FPS para não competir tanto com o ALVR. Isso é independente do FPS do jogo.

## Estrutura do projeto

- `.github/workflows/android.yml`: pipeline de build no GitHub Actions.
- `tools/patch_phonevr.py`: aplica mudanças verificadas ao código upstream do PhoneVR.
- `android/phonevr-overlay/BridgeTracking.kt`: ARCore 6DOF + aquisição de frames da câmera.
- `android/phonevr-overlay/BridgeMjpegServer.kt`: endpoint MJPEG usado pelo script das mãos.
- `pc/hands__66_ (2).py`: rastreador MediaPipe/VMT original.
- `pc/hand_landmarker.task`: modelo do MediaPipe.
- `pc/start_hands.bat`: inicia o rastreador e pergunta o IP do telefone.

## Limitações conhecidas deste primeiro candidato

- Ainda não foi compilado nesta sessão; o resultado de compilação virá do Actions.
- A pose ARCore é recentrada na primeira amostra válida. A orientação/eixos e a altura precisam ser conferidos fisicamente depois de instalar.
- A câmera é fornecida ao script de mãos por MJPEG; o vídeo principal e o tracking da cabeça usam ALVR.
- A meta de 24 FPS depende do jogo, da resolução, do encoder x264 e da carga no PC. A GT 710/i3-9100F pode ficar abaixo disso.
- A compatibilidade do cliente PhoneVR/ALVR está documentada para ALVR Server v20.8.0; não atualize o servidor antes do primeiro teste.

## Créditos e licenças

O cliente Android é construído a partir de PhoneVR (GPL-3.0) e do fork ALVR-phonevr (MIT), com os patches locais listados acima. Consulte os repositórios e licenças originais:

- https://github.com/PhoneVR-Developers/PhoneVR
- https://github.com/PhoneVR-Developers/ALVR-phonevr
- https://github.com/alvr-org/ALVR
