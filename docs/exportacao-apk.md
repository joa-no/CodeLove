# Exportação de jogos Android — arquitetura inicial

## Situação

A variante Android `embed` do LÖVE já existe no CodeLove. `GameActivity.hasEmbeddedGame()` aceita um `main.lua` ou `game.love` na raiz dos assets do APK. O CodeLove mantém o editor na variante `normal`, e a edição dos arquivos do projeto usa SAF diretamente.

**Esta etapa não adiciona exportação de APK ao aplicativo Android.** Implementa apenas o preparo de assets para uma compilação em checkout isolado. Não há compilador Android embutido no celular nem envio automático de projetos ao GitHub.

## Fluxo de exportação proposto

1. O usuário salva explicitamente todas as abas antes de exportar. Se houver arquivos pendentes, bloquear a exportação e informar o motivo.
2. O usuário configura nome, pacote Android, versão numérica, versão legível e ícone.
3. Ler a árvore SAF selecionada com permissão explícita e preparar um pacote transitório **somente para exportação**. Não mudar a pasta principal SAF nem converter a cópia temporária em workspace.
4. No ambiente de compilação isolado, validar `main.lua`, tamanho, quantidade de arquivos e caminhos, e transferir o conteúdo para `app/src/embed/assets`.
5. Compilar a variante `embed` usando parâmetros `-Papp.name`, `-Papp.application_id`, `-Papp.version_name`, `-Papp.version_code`. Garantir que o APK exportado não tenha os componentes de editor nem o executor especial do CodeLove.
6. Assinar com uma chave de distribuição escolhida pelo usuário, **nunca** com a chave pública de debug do CodeLove nem automaticamente com a chave privada usada para assinar o CodeLove.
7. Disponibilizar o APK final pelo seletor de documentos do Android e limpar todos os artefatos temporários.

## Preparação dos assets (implementada)

Num checkout de build isolado, com o conteúdo do jogo em `/tmp/meu-jogo`:

```bash
python3 tools/prepare_game_export.py \
  --project /tmp/meu-jogo \
  --name "Meu Jogo" \
  --package com.exemplo.meujogo \
  --version-name 1.0.0 \
  --version-code 1
```

A ferramenta recusa destino não vazio (exceto `.gitkeep`), `main.lua` ausente, links simbólicos, projetos com mais de 512 MiB ou 10.000 arquivos, nomes vazios, IDs de pacote inválidos e versões numéricas não positivas. Ela apenas copia os arquivos e imprime uma sugestão de comando Gradle. Não modifica a pasta de origem.

## Itens ainda não implementados

- Interface de exportação no CodeLove, integrada ao tema e traduzida.
- Verificação de alterações não salvas e coleta da árvore SAF, com limites e exclusões apropriados.
- Canal seguro para levar o projeto ao ambiente de build. Não pressupor acesso GitHub do usuário final.
- Ajuste do template `embed` para produzir somente um jogo independente, sem canal de comandos, console, seleção de pastas ou interface CodeLove.
- Ícone personalizado e eventual tela de abertura do jogo.
- Estratégia de assinatura por jogo, armazenamento seguro das chaves e atualizações.
- Compilação automática, relatório de erros, entrega e limpeza do APK.
- Testes de instalação em dispositivo, libs nativas, `require`, imagens, áudio, rotação, permissões e atualização da mesma assinatura.

## Restrições importantes

- Um APK Android executável exige SDK/NDK, Gradle e bibliotecas nativas. A variante `embed` não faz esse trabalho dentro do Android por si só.
- O cache de execução do CodeLove não é o diretório de trabalho permanente: exportar deve partir dos arquivos SAF salvos.
- Não enviar código privado de projetos ou chaves privadas a um servidor sem consentimento e modelo de segurança definidos.
- Não reutilizar automaticamente a chave de assinatura de release do CodeLove para jogos exportados.
- Preservar licenças do LÖVE e dependências no aplicativo exportado.
- Somente ativar o botão de exportação quando existir um fluxo realmente funcional e testado de ponta a ponta.
