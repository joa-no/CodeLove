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

## Pendências obrigatórias antes de habilitar “Gerar APK”

1. Validar o novo empacotamento direto em um APK real, incluindo alinhamento de 16 KiB, assinatura verificável e execução no Android.
2. Implementar a geração ou importação de chave **persistente por jogo**, com backup/reimportação e proteção contra perda da identidade de assinatura. Não reutilizar a chave do CodeLove nem chaves efêmeras de CI.
3. Implementar personalização confiável do `applicationId`, nome, versão, versionCode e ícone no APK compilado. Isso requer tratar corretamente manifesto binário e recursos; não presumir que um simples ZIP resolve.
4. Incorporar o APK-base ao CodeLove de forma offline, sem acesso à rede do usuário ou compilações por projeto.
5. Integrar empacotamento, assinatura e salvamento do APK por SAF num fluxo assíncrono seguro; testar instalação e **atualização** sem desinstalar os jogos.

As implementações experimentais devem permanecer isoladas e reversíveis. Quando algo falhar, corrigir ou restaurar a última abordagem validada em vez de acumular protótipos.

## Histórico de limpeza

Foi descartada a proposta de compilação remota. Removidos o pacote de solicitações `codelove.android-build.v1`, `GameExportBuildRequest.java`, `tools/game_build_request.py`, `tools/test_game_build_request.py`, `tools/build_export_apk.py` e a opção de UI para prepará-lo. Os componentes úteis para exportação offline foram preservados.
