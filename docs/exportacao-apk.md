# Exportação de jogos Android — direção offline

## Objetivo

Permitir que o CodeLove produza um APK instalável no próprio Android, **sem hospedagem, servidor de compilação, conta GitHub do usuário ou cobrança recorrente**.

O LÖVE Android possui uma variante `embed` que embute arquivos Lua e recursos do jogo em um APK. Essa compilação já foi confirmada pelo workflow de teste. Entretanto, a compilação tradicional utiliza Gradle, SDK e NDK — não é um exportador offline integrado ao aplicativo.

A arquitetura offline ainda precisa ser comprovada antes de ativarmos o botão "Gerar APK".

## Componentes aproveitáveis já implementados

- **Painel Exportar APK:** configura nome, identificador Android, versão, código de versão e ícone. As configurações ficam locais no CodeLove.
- **Arquivamento SAF:** `GameExportArchive.java` lê os documentos salvos da pasta do projeto e cria um arquivo `.love` ZIP. A geração exige `main.lua` na raiz e possui limites de tamanho, profundidade e quantidade de arquivos.
- **Opção "Salvar pacote .love":** `GameExportPackage.java` oferece gravação via seletor de documentos Android, sem alterar a pasta original nem as abas do editor.
- **Preparação do LÖVE Android:** `tools/prepare_game_export.py` valida uma pasta de jogo ou um `.love` e prepara os assets da variante `embed`. Para o processo de construção de um APK-base, também consegue preparar os recursos do ícone com Pillow.
- **Workflow de verificação:** `export-smoke-test.yml` constrói um jogo sintético com a variante `embed`, verifica nome, assets e ícone, e assina uma cópia exclusivamente de teste com uma chave temporária. Isso comprova o processo Gradle tradicional, **não** a exportação offline no telefone.

O editor, a árvore SAF, o runtime do CodeLove e o ícone principal do aplicativo devem permanecer separados do APK final do jogo.

## Trabalho removido

A tentativa de compilação remota introduziu o formato `codelove.android-build.v1` e scripts para validar/compilar esse pacote em um serviço externo. Essas funções foram retiradas do aplicativo e do repositório porque não atendem ao objetivo offline.

Os arquivos removidos foram:
- `GameExportBuildRequest.java`
- `tools/game_build_request.py`
- `tools/test_game_build_request.py`
- `tools/build_export_apk.py`

Também foram retirados o botão **Preparar pacote de compilação**, suas traduções, a entrada `--request` do preparador de assets e o teste que consumia esse formato. As ferramentas `--love`, `--project` e `--icon` continuam disponíveis para os testes com a variante `embed`.

## Próxima investigação: APK-base offline

1. **Preparar um APK-base com o LÖVE Android**: runtime nativo e estrutura mínima, sem jogo próprio e sem componentes da interface do CodeLove.
2. **Provar a substituição local do jogo**: incluir `main.lua` e assets da pasta SAF no APK-base, sem precisar recompilar código nativo. Verificar integridade ZIP, alinhamento das bibliotecas, execução e compatibilidade.
3. **Avaliar a personalização de metadados e recursos**: identificador de pacote, nome, versão e ícone no manifesto/recursos Android compilados; não presumir que bastará substituir um ZIP.
4. **Validar assinatura de APK offline e persistente**: gerar/importar uma chave por jogo com backup, assinar o APK no Android e confirmar que uma versão posterior atualiza a anterior sem desinstalar.
5. **Só então ligar a montagem ao painel "Gerar APK"** e salvar o APK final via SAF.

Não reutilizar a chave privada do CodeLove nem usar uma chave pública de debug para distribuir jogos. Não inserir artefatos transitórios na pasta principal como workspace.

## Estado da validação

O teste automatizado #11 do processo `embed` anterior passou. A implementação de exportação offline **ainda não foi testada** e não deve ser apresentada como funcional. A assinatura de teste do workflow não prova assinatura local nem persistência de chave.

Distribuição inicial prevista: APK pelo GitHub, sem dependência da Google Play.
