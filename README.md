# VR Phone Bridge — beta 0.5.1 (ALVR)

Esta é uma revisão de correção do pacote-fonte 0.5. Ela ainda precisa compilar no GitHub Actions e ser testada no Moto G60/Windows; o ZIP não é um APK já validado.

## Arquitetura

```text
Moto G60                                                   PC Windows
ARCore 6DOF ──► cliente PhoneVR/ALVR ── Wi-Fi ───────────► ALVR Server 20.8.0
Câmera YUV ───► MJPEG :8080/video ───────────────────────► MediaPipe/VMT (mãos)
Jogo SteamVR ──► driver ALVR ──► vídeo H.264 estéreo ────► cliente Android ALVR
```

- O VRto3D e o antigo `relay.py` que capturava a área de trabalho não fazem parte deste fluxo.
- O HMD do SteamVR é registrado pelo driver ALVR. O app do celular é o cliente de streaming.
- O `noGvr` é a variante PhoneVR que usa o cliente ALVR; o campo inicial de IP do PC faz parte desse fluxo. Não existe um seletor extra de modo nesse APK.
- A câmera/MJPEG para as mãos é um serviço separado iniciado quando a Activity ALVR do app está aberta. O script `start_hands.bat` não inicia nem conecta o ALVR.

## Correções nesta revisão

1. PhoneVR está fixado na tag `v2.0.0-beta`, a release que declara integração com ALVR `20.8.0`; o workflow não baixa `master` móvel.
2. O workflow retira a linha `cargo update` do helper upstream para não atualizar as dependências travadas do release durante o build.
3. O manifesto agora declara `CAMERA`, `INTERNET` e `ACCESS_NETWORK_STATE`, marca câmera como recurso opcional e declara ARCore opcional. Antes o app pedia `CAMERA` em runtime sem declarar essa permissão no manifesto.
4. A ponte de ARCore/MJPEG está em Java puro, sem adicionar plugin Kotlin ao projeto PhoneVR.
5. O workflow valida o patch antes de compilar e inclui o verificador `check_phone_camera.bat` no pacote de mãos.

## Versões

- ALVR Server para Windows: **20.8.0**.
- Cliente Android: PhoneVR `v2.0.0-beta`, flavor `noGvr`, com os patches deste repositório.
- Não misture esta combinação com outras versões de cliente/servidor durante o primeiro teste.

## Atualizar a branch `beta`

1. Extraia o ZIP.
2. Na branch `beta`, substitua `.github/workflows/android.yml` pelo workflow deste pacote.
3. Substitua as pastas `android/phonevr-overlay`, `pc`, `tools` e o `README.md`, diretamente na raiz do repositório.
4. A raiz deve conter `.github`, `android`, `pc`, `tools` e `README.md`.
5. Faça commit diretamente em `beta` (não crie Pull Request para `main`).
6. Em **Actions**, abra `Build VR Phone Bridge (ALVR)`. O artefato esperado é `VRPhoneBridge-ALVR-beta`.
7. O artefato contém `VRPhoneBridge-ALVR.apk` e `VRPhoneBridge-PC-hands.zip`.

O workflow compila o flavor `noGvr` a partir da tag fixada. Se o build falhar, não instale um APK antigo achando que é o novo: abra o job que falhou e confira o primeiro erro vermelho.

## Primeira conexão ALVR — sequência correta

### No PC

1. Instale/inicie o **ALVR Server 20.8.0** pelo ALVR Launcher.
2. Termine a configuração inicial do Streamer no Dashboard.
3. Deixe PC e Moto G60 na mesma rede local Wi-Fi. Desative VPN durante o primeiro pareamento.
4. No Dashboard, use o botão **Launch SteamVR** (ou **Restart SteamVR**) para carregar o driver ALVR. O Dashboard sozinho consegue editar configurações, mas a descoberta de clientes depende do driver carregado pelo SteamVR.

### No Moto G60

1. Abra **VR Phone Bridge**.
2. Na tela de endereço, informe o IPv4 do PC (no Windows, `ipconfig` → adaptador Wi-Fi/Ethernet → IPv4).
3. Confirme o botão de continuar/conectar da própria tela. O app precisa sair dessa página e abrir a tela cliente/VR. Se permanecer somente no campo de IP, o cliente ainda não entrou na Activity ALVR.
4. Conceda a permissão de câmera quando o Android solicitar. Ela é usada pela ponte ARCore e pela câmera MJPEG para as mãos.
5. Mantenha o app aberto enquanto o ALVR faz a descoberta.

### Parear no ALVR

- A descoberta ALVR é iniciada pelo cliente por broadcast UDP na porta `9943`. Quando o driver está rodando, o cliente aparece na área **New devices** do Dashboard; clique em **Trust**.
- Um dispositivo que você adicionou manualmente com hostname inventado ou incompleto pode ficar como `Disconnected`. Remova essa entrada manual incompleta e deixe a descoberta automática funcionar primeiro.
- O pareamento manual requer o hostname exato que aparece na tela de boas-vindas do cliente, além do IP do telefone; IP sozinho não basta.

Se o cliente não aparecer em **New devices** mesmo com SteamVR iniciado:

1. Confirme que os dois aparelhos estão na mesma sub-rede Wi-Fi e que o roteador não usa `AP isolation`/isolamento de clientes.
2. Permita ALVR Streamer/Dashboard e `vrserver.exe` no Firewall do Windows para redes **Privadas**. O ALVR Dashboard tem ferramenta de instalação/configuração do firewall.
3. Verifique se UDP `9943` (descoberta) e as portas de comunicação do ALVR não estão bloqueadas. A documentação ALVR identifica UDP 9943 como porta de descoberta.
4. Não desative permanentemente o Firewall do Windows.

## Tracking das mãos — separado do ALVR

O app expõe a câmera do Moto G60 em `http://IP_DO_CELULAR:8080/video` somente quando a Activity ALVR e a ponte de câmera estão ativas.

1. Extraia `VRPhoneBridge-PC-hands.zip`.
2. Execute `check_phone_camera.bat` e informe o **IPv4 do CELULAR**. Ele consulta `/` e `/shot.jpg`.
3. `HTTP 200` em `/shot.jpg` indica que existe um frame de câmera. `HTTP 503` indica que o servidor está ativo, mas ainda não produziu frame ARCore.
4. Só depois execute `start_hands.bat` e informe novamente o **IP do CELULAR**. Ele executa MediaPipe e envia as mãos via VMT/OSC para `127.0.0.1:39570`.
5. O driver/receiver VMT precisa estar instalado e ativo no PC, conforme o setup de mãos anterior.

**Os dois campos de IP têm destinos diferentes:**

- Campo dentro do app Android: IP do **PC** (ALVR Streamer).
- Campo dos `.bat` de mãos no PC: IP do **celular** (câmera MJPEG `:8080`).

O `start_hands.bat` não abre SteamVR, não faz o pareamento ALVR e não transmite a imagem do jogo.

## Perfil inicial de desempenho

Meta de teste: tentar chegar perto de **24 FPS**, sem garantia. Comece com H.264, resolução/escala baixa (50% ou menos) e bitrate modesto. A GT 710 não deve ser presumida compatível com NVENC moderno; use x264/software se o ALVR oferecer essa opção. Se o i3-9100F não sustentar, reduza resolução e bitrate.

O stream MJPEG para as mãos é reduzido e limitado a cerca de 8 FPS para não competir tanto com o VR.

## Diagnóstico

- **O app fica preso na tela do IP do PC:** ele ainda não entrou na Activity de streaming. Confirme se você apertou o botão da tela depois de informar o IPv4; veja os logs Android se continuar.
- **ALVR não mostra New devices:** verifique se SteamVR foi iniciado pelo Dashboard (driver carregado), rede/sub-rede, VPN, isolamento Wi-Fi e firewall UDP 9943.
- **Uma linha manual aparece Disconnected:** isso não significa que o cadastro por IP conectou. Remova a entrada incompleta; o ALVR primeiro precisa receber a descoberta do cliente.
- **`check_phone_camera.bat` não conecta:** o app não chegou à Activity ALVR, a permissão de câmera não foi concedida ou a rede/firewall bloqueou a porta 8080.
- **`/` responde e `/shot.jpg` retorna 503:** o servidor MJPEG está aberto, mas o ARCore ainda não entregou um frame; confira a permissão e a disponibilidade do Google Play Services for AR.
- **Vídeo conecta, mas há somente rotação:** ARCore não iniciou ou não está rastreando; nesse caso o cliente usa o fallback de orientação Cardboard.

## Limitações honestas

- O ZIP é fonte + workflow; ele precisa passar pelo GitHub Actions e por teste físico antes de ser considerado funcional.
- A posição 6DOF do ARCore e a transformação dos eixos precisam ser confirmadas no aparelho real.
- 24 FPS depende do jogo, resolução, bitrate e codificação por software. A GT 710 pode ficar abaixo disso.
- O ícone do HMD vem do driver ALVR; o app não transforma o telefone em um Quest físico.

## Créditos e licenças

O cliente Android é baseado em PhoneVR `v2.0.0-beta` (GPL-3.0) e ALVR-phonevr (MIT), com patches locais em `tools/patch_phonevr.py` e `android/phonevr-overlay/`. Consulte os repositórios e licenças originais:

- https://github.com/PhoneVR-Developers/PhoneVR
- https://github.com/PhoneVR-Developers/ALVR-phonevr
- https://github.com/alvr-org/ALVR
