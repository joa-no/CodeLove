# CodeLove — Exportação Android

## Status em novembro de 2026

O CodeLove possui um painel para configurar nome, pacote Android, versão e ícone. O painel permite salvar um arquivo `.love` e preparar um **pacote de compilação** (`.zip`). Nenhum desses arquivos é um APK instalável.

O compilador isolado do projeto já gerou e verificou um APK Release de teste a partir de um jogo LÖVE, com ícone personalizado e assinatura **temporária**. Esse teste não garante execução num celular nem garante que uma chave de distribuição possa ser reutilizada.

**Ainda não existe compilação automática a partir do botão do CodeLove.** Não há endpoint de build, autenticação de usuários, gestão permanente de chaves ou sistema de entrega de APK configurados. Portanto, o botão "Gerar APK" não pode ser habilitado.

## Pacote de compilação v1

A opção **Preparar pacote de compilação** do painel guarda um ZIP selecionado pelo usuário. Ele contém:

| Entrada | Conteúdo |
|---|---|
| `request.json` | Configuração do projeto, formato e hashes |
| `game.love` | Arquivos salvos do projeto, lidos diretamente do SAF |
| `icon.png` | Ícone opcional, recortado centralmente para 512×512 |

O campo `format` é `codelove.android-build.v1`. Os campos `name`, `package`, `version_name` e `version_code` representam a identidade Android do jogo. `game_love` e `icon_png` incluem `size` e `sha256`. Nenhuma senha, token, chave de assinatura ou URL de servidor é incluída.

O CodeLove não altera os documentos da pasta SAF. Se alguma aba contiver alterações não salvas, bloqueia a preparação. O arquivo temporário é criado antes de abrir o seletor de destino, evitando que ele inclua a si mesmo quando salvo dentro do projeto.

**Privacidade:** esse ZIP contém o código-fonte e os recursos do jogo, potencialmente privados. Não o publique em repositórios públicos. Nenhum envio para a internet é feito por essa funcionalidade.

## Uso pelo compilador isolado

O ambiente de compilação aceita uma pasta local (`--project`), um arquivo `.love` (`--love`) ou o pacote inteiro:

```bash
python3 tools/prepare_game_export.py \
  --request /tmp/meu-jogo-android-build.zip
```

O pacote é conferido antes de ser extraído: estrutura exata, formatos, nomes, versão, identificador Android, tamanhos, SHA-256 e presença de `main.lua`. A entrada `icon.png`, quando presente, produz ícones no source set `embed` sem afetar o ícone do CodeLove.

O utilitário `prepare_game_export.py` prepara assets no checkout isolado e informa a linha de comando Gradle correspondente ao nome e versão declarados no pacote. **Ele não compila nem assina o APK.**

Para um ambiente de compilação Android já configurado, o novo comando `build_export_apk.py` executa o ciclo completo **do lado do compilador**: valida o pacote, prepara assets, compila o jogo, verifica a assinatura e copia o APK assinado para o destino indicado:

```bash
python3 tools/build_export_apk.py \
  --request /tmp/meu-jogo-android-build.zip \
  --output /tmp/meu-jogo.apk
```

Antes da execução, devem existir uma chave PKCS#12 **do próprio jogo** e as variáveis protegidas `GAME_SIGNING_STORE_FILE`, `GAME_SIGNING_STORE_PASSWORD`, `GAME_SIGNING_KEY_ALIAS` e `GAME_SIGNING_KEY_PASSWORD`. Se faltarem, o comando recusa a compilação de distribuição. Não coloque a chave nem as senhas dentro do ZIP ou em código versionado. Mantenha backup seguro e reutilize a mesma chave nas atualizações do jogo.

Esse comando só funciona em um checkout isolado com Java, Gradle, SDK/NDK e as dependências Android disponíveis. **Não está embutido no celular nem vinculado a um serviço de build remoto.**

O workflow `export-smoke-test` executa testes da entrada e monta um jogo sintético em `assembleEmbedNoRecordRelease`, assinado com chave gerada exclusivamente para o teste. Ele verifica conteúdo, ícone, identidade Android e assinatura usando ferramentas oficiais. Nenhum jogo real ou chave privada é enviado ao GitHub por esse teste.

## Etapas ainda necessárias para exportação diretamente no CodeLove

1. **Transporte autenticado:** implementar e hospedar um serviço de compilação confiável, capaz de receber o pacote por HTTPS, criar um trabalho isolado, fornecer status e devolver o APK. O endereço do serviço e as credenciais ainda não existem; não há razão para pedir permissões gerais da conta GitHub do usuário.
2. **Assinatura por jogo:** preservar uma chave estável, permitir backup/recuperação pelo proprietário e garantir que versões futuras usem a mesma identidade criptográfica. Não reaproveitar a chave privada do CodeLove. Chaves temporárias dos testes não são adequadas para distribuição.
3. **Cliente Android:** quando houver um endpoint funcional, enviar o pacote somente mediante consentimento claro, acompanhar o build, baixar o arquivo validado e salvar o APK pelo SAF.
4. **Testes em aparelho:** instalar, iniciar, testar carregamento de scripts, imagens/áudio, requisitos e atualização de uma versão instalada.

## Restrições de implementação

- O compilador Android depende de Gradle, SDK, NDK e bibliotecas nativas. O aplicativo Android atual não embute esse ambiente.
- A variante `embed` deve manter o jogo independente do editor. Continuar verificando que não há runtime especial de CodeLove no APK exportado.
- Respeitar licenças do LÖVE e demais dependências.
- Falhas não devem modificar a pasta principal nem perder arquivos não salvos.
- Não habilitar um botão chamado **Gerar APK** até que o caminho completo de compilação, assinatura e entrega esteja funcional e testado.
