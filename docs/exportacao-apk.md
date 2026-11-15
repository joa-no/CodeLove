# CodeLove — exportação Android totalmente offline

## Objetivo e restrições

O usuário deve poder gerar um APK independente de seu jogo LÖVE2D **diretamente no CodeLove no Android**, sem servidor, hospedagem, acesso obrigatório à internet, ferramentas instaladas separadamente ou credenciais GitHub.

A variante Android `embed` do LÖVE2D permite empacotar um jogo. Na documentação oficial do LÖVE 11.4+, o formato recomendado é incluir **`assets/main.lua` e demais arquivos diretamente em `assets/`**. O método `assets/game.love` continua reconhecido pelo fork, mas é legado e não será o formato definitivo.

O processo deve **reutilizar um APK-base já compilado**, sem levar Gradle/SDK/NDK para o dispositivo. GitHub Actions participa dos builds e testes do CodeLove, não da exportação dos usuários.

## Componentes implementados

- **Interface de configurações:** `GameExportDialog.java` permite configurar nome, applicationId, versão, versionCode e ícone, e salvar preferências por projeto. Não tem botão de APK funcional.
- **Empacotamento do projeto salvo:** `GameExportArchive.java` lê arquivos via SAF, valida `main.lua`, limita arquivos/profundidade/tamanho e produz `.love`. `GameExportPackage.java` oferece a opção de salvá-lo pelo seletor Android.
- **Montador offline:** `app/src/normal/java/org/love2d/android/OfflineGameApk.java` não depende das APIs Android e produz ZIP APK **não assinado**. A classe preserva as bibliotecas nativas e seu alinhamento de 16 KiB, valida o ZIP e remove assinaturas antigas.
  - `writeUnsigned`: substitui `assets/game.love`, método legado, mantido como referência funcional.
  - `writeUnsignedAssets`: lê o `.love` e incorpora `assets/main.lua`, scripts e recursos **diretamente**, conforme o formato recomendado. Aceita um APK-base com um único placeholder `assets/game.love` ou `assets/main.lua`, e rejeita colisões com os assets do runtime.
- **Assinador isolado:** `OfflineApkSigner.java` usa `apksig` e uma chave PKCS#12 fornecida pelo chamador; gera e verifica assinaturas v1/v2/v3 e compara certificados. A dependência foi limitada ao flavor `normal` do CodeLove, não aos APKs dos jogos.
- **Testes:** `tools/OfflineGameApkTest.java`, `tools/OfflineApkSignerTest.java`, `tools/test_offline_apk_prototype.py` e `.github/workflows/offline-apk-prototype.yml`.

## Resultados de testes

- **Teste offline #4: aprovado.** Substituição de `game.love`, alinhamento nativo, assinatura de teste e verificação do APK real.
- **Teste offline #6: assinatura Java v1/v2/v3 aprovada em dois APKs, com o mesmo certificado.** O job foi marcado como falho porque um `grep` procurava um texto incompatível com a saída atual do `apksigner`. A comparação foi corrigida sem alterar o algoritmo de assinatura.
- **Teste offline #12:** os testes unitários da montagem Java e da integração do assinador passaram. O teste completo do APK real, agora com os assets diretos, ainda não tinha resultado final na última consulta.

**Não há comprovação de instalação e execução no celular**, e ainda não foi testado o assinador dentro do processo Android do CodeLove. Um teste no JVM do GitHub Actions não prova compatibilidade no dispositivo.

## Chaves de assinatura: geração, armazenamento e recuperação

A etapa de **criação e backup offline** foi adicionada, ainda sem interface:

- `GameSigningKeyStore.java`: cria um par RSA de 3072 bits e um certificado X.509 próprio do jogo, salva o material em PKCS#12 protegido por senha de no mínimo 12 caracteres, confere a chave privada e permite exportar e restaurar o mesmo certificado por `InputStream`/`OutputStream`. Uma chave existente nunca é substituída automaticamente.
- `GameSigningKeyManager.java`: usa exclusivamente `Context.getNoBackupFilesDir()/game-signing-keys`, com nome derivado por SHA-256 do **ID estável do projeto SAF**, não do nome editável do jogo. Nenhuma senha é salva nas preferências. O diretório é privado e excluído do backup automático do Android.
- `tools/GameSigningKeyStoreTest.java` e `game-signing-key-tests.yml`: verificam no JVM geração, senha errada, backup, restauração, identidade preservada, chaves distintas e compatibilidade com o formato de KeyStore usado pelo assinador. **Teste #1 aprovado.**
- Dependências Bouncy Castle `bcprov-jdk18on:1.86` e `bcpkix-jdk18on:1.86` limitadas à variante `normal`, pois o Android não disponibiliza uma API pública completa para emitir certificados de chaves de software exportáveis. O mecanismo de assinatura usa `apksig` separadamente.

**Atenção:** o armazenamento privado protege os arquivos de outros apps comuns, mas não equivale a uma chave não exportável de hardware. O formato PKCS#12 permite backup; a senha e o arquivo de backup precisam ser guardados pelo usuário. **Desinstalar o CodeLove ou limpar seus dados apaga a cópia local**. Sem o backup e a senha, uma nova versão do jogo não poderá ser instalada como atualização com a mesma assinatura.

`GameSigningKeyManager` ainda não está conectado ao seletor SAF, painel ou exportação real. A compatibilidade de geração, PKCS#12 e assinatura **dentro de um dispositivo Android** também precisa ser validada antes de disponibilizar a função.

## Pendências obrigatórias antes de habilitar “Gerar APK”

1. Validar o novo empacotamento direto em um APK real, incluindo alinhamento de 16 KiB, assinatura verificável e execução no Android.
2. Integrar a chave por jogo ao painel e ao SAF, incluindo criação, backup manual, importação e solicitação segura da senha; confirmar a geração e assinatura no Android real. Nunca reutilizar a chave do CodeLove nem chaves efêmeras de CI.
3. Implementar personalização confiável do `applicationId`, nome, versão, versionCode e ícone no APK compilado. Isso requer tratar corretamente manifesto binário e recursos; não presumir que um simples ZIP resolve.
4. Incorporar o APK-base ao CodeLove de forma offline, sem acesso à rede do usuário ou compilações por projeto.
5. Integrar empacotamento, assinatura e salvamento do APK por SAF num fluxo assíncrono seguro; testar instalação e **atualização** sem desinstalar os jogos.

As implementações experimentais devem permanecer isoladas e reversíveis. Quando algo falhar, corrigir ou restaurar a última abordagem validada em vez de acumular protótipos.

## Histórico de limpeza

Foi descartada a proposta de compilação remota. Removidos o pacote de solicitações `codelove.android-build.v1`, `GameExportBuildRequest.java`, `tools/game_build_request.py`, `tools/test_game_build_request.py`, `tools/build_export_apk.py` e a opção de UI para prepará-lo. Os componentes úteis para exportação offline foram preservados.
