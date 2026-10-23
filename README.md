# Leitor de Códigos — Android 1.0.0

Aplicativo Android com leitura contínua pela câmera, armazenamento local e exportação TXT/CSV.

## Recursos
- Leitura contínua pela câmera com Google ML Kit.
- EAN-13, EAN-8, UPC, Code 128, Code 39, ITF, QR Code e outros formatos compatíveis.
- Registra códigos repetidos quando cada ocorrência é uma nova aparição: retire o código do campo de visão por um instante antes de ler novamente o mesmo item.
- Digitação manual, lanterna, lista persistente e remoção individual por toque longo.
- Exportação TXT, CSV somente com códigos ou CSV com número sequencial + código.
- Compartilhamento pelo menu do Android, incluindo WhatsApp se instalado.
- Armazenamento e leitura offline após a instalação.

## Como gerar o APK sem instalar Android Studio
O projeto inclui um workflow do GitHub Actions para compilar o APK na nuvem.

1. Entre em https://github.com e crie uma conta, se ainda não tiver.
2. Crie um repositório novo e envie todos os arquivos e pastas deste projeto (incluindo `.github/workflows/build-apk.yml`).
3. No repositório, abra **Actions** e selecione **Build Android APK**.
4. Clique em **Run workflow** (ou aguarde o build iniciado após enviar os arquivos).
5. Quando terminar, abra a execução concluída e baixe o artefato **LeitorCodigoBarras-APK**.
6. Dentro do ZIP do artefato estará `app-debug.apk`.

## Instalar no Motorola
1. Transfira/baixe `app-debug.apk` no Motorola.
2. Abra o arquivo usando o app Arquivos.
3. Se solicitado, permita que o app usado para abrir o APK instale apps desconhecidos.
4. Toque em Instalar e depois em Abrir.
5. Conceda a permissão de câmera.

## Observações
- O primeiro build precisa de internet para baixar dependências.
- Este é um APK de depuração para testes, não publicado na Play Store.
- Teste os formatos de códigos usados no seu trabalho antes de depender dele em uma operação crítica.
