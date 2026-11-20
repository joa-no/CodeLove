# CodeLove — exportação de jogos Android offline

## Estado atual (19/11/2026)

O CodeLove permite **gerar e assinar APKs independentes de jogos LÖVE2D no próprio Android**, sem hospedagem, servidor de compilação ou ferramentas de desenvolvimento adicionais instaladas no dispositivo.

A interface **Exportar APK** está integrada ao editor e permite configurar nome do jogo, identificador de pacote, versão, código de versão e ícone, gerenciar uma chave PKCS#12 por identificador de pacote e salvar o APK pelo seletor de documentos do Android.

O fluxo principal foi validado por testes automatizados e em dispositivo real. Isso **não garante compatibilidade com todos os jogos, versões de Android ou situações de instalação/atualização**. A versão pública 1.0.0 ainda não foi lançada.

## Arquitetura do exportador

1. **Template:** o GitHub Actions compila uma variante `embed` não assinada do runtime LÖVE Android e a inclui nos assets do aplicativo CodeLove. O usuário não precisa ter Gradle, SDK, NDK ou acesso à internet.
2. **Arquivos do jogo:** `GameExportArchive` lê o projeto diretamente do SAF, exige `main.lua`, valida caminhos e limites e prepara os arquivos `.love` em cache temporário.
3. **Montagem:** `OfflineGameApk.writeUnsignedAssets` incorpora o conteúdo do projeto diretamente em `assets/`, substitui o placeholder do template, atualiza o manifesto binário e os recursos do ícone, preserva o alinhamento das bibliotecas nativas e adiciona um carimbo discreto em `META-INF/CODELOVE.txt`, sem alterar os arquivos do jogo.
4. **Assinatura:** `OfflineApkSigner` assina o APK com a identidade PKCS#12 específica do jogo e verifica a assinatura antes de disponibilizar o arquivo final.
5. **Salvamento:** `GameExportApk` abre o seletor Android para salvar o APK assinado no destino escolhido pelo usuário. O CodeLove não modifica os arquivos originais do projeto.

Os componentes ficam em `app/src/normal/java/org/love2d/android/`, com entrada de interface em `GameExportDialog.java` e empacotamento SAF em `GameExportArchive.java`.

## Assinatura e backup .p12

- A chave é associada ao **identificador de pacote Android**; renomear ou mover a pasta do projeto não altera essa associação.
- A senha da chave deve ter **pelo menos 12 caracteres** na criação. O CodeLove não armazena essa senha.
- O armazenamento primário fica no diretório privado `getNoBackupFilesDir()/game-signing-keys`, excluído do backup automático do Android.
- **É obrigatório fazer backup manual do .p12 e guardar a senha**. Desinstalar o CodeLove ou limpar seus dados elimina a cópia local; sem a chave anterior, a atualização de um jogo já distribuído pode se tornar impossível.
- Uma chave existente não é substituída automaticamente. A restauração só é admitida quando o pacote ainda não tem uma chave local.
- O backup valida a senha **antes** de abrir `ACTION_CREATE_DOCUMENT`, para não criar arquivos vazios quando a senha está errada. Em erros posteriores de gravação, o CodeLove também tenta excluir o documento incompleto (sujeito às permissões e ao provedor de arquivos).

## Estado dos testes

| Validação | Estado |
| --- | --- |
| Compilação CodeLove Debug/Release | Aprovada: GitHub Actions #44633701383, segunda tentativa (a primeira falhou ao obter dependências) |
| Montagem, personalização, alinhamento e assinatura de exemplo | Aprovados no workflow `offline-apk-prototype` (#44602896192) |
| Criação de chave, backup e senha incorreta no Android | Testados pelo usuário, sem problemas após correções da build #44619884516 |
| Exportação de APK independente e inicialização de jogo no Android | Confirmadas pelo usuário |
| Projeto com imagens e sons (Star Guard) | Usuário confirmou que o APK exportado após a limpeza abre e funciona no Android; compatibilidade universal não comprovada |
| Instalar uma nova versão sobre a anterior, com a mesma chave | Confirmado: atualizações sucessivas com `versionCode` crescente, sem desinstalação |
| Restaurar .p12 e exportar atualização com certificado preservado | Confirmado em teste real: certificado SHA-256 permaneceu igual após restauração e exportação |
| Isolamento do runtime exportado (sem classes ou recursos do editor) | Aprovado no workflow #44633701383 e em APK StarGuard inspecionado; jogo também testado em Android |\n| Carimbo `META-INF/CODELOVE.txt` no APK exportado e assinado | Implementado no código e com testes adicionados; ainda requer execução de CI e nova exportação real |\n| Jogos com vários dispositivos, versões Android, permissões e recursos | **Cobertura ainda parcial** |

O aviso de compatibilidade do LÖVE, quando `t.version` em `conf.lua` difere da versão do runtime, pertence ao motor e **não significa falha de empacotamento**. O exportador não altera automaticamente `conf.lua`.

## Plano para estabilizar a 1.0.0

1. **Concluído — instalação e atualização:** atualizações sucessivas testadas sem desinstalação, usando pacote e certificado estáveis. Não foi estabelecida uma garantia de preservação de todos os dados para todos os jogos.\n2. **Concluído — recuperação de assinatura:** backup e restauração `.p12` testados; certificado conferido após exportar atualização. Preservar sempre uma cópia externa e a senha.\n3. **Concluído — isolamento do runtime:** classes, recursos e dependências do editor removidos do template, com verificações automatizadas e execução real do StarGuard.\n4. **Pendente — validar o novo carimbo:** executar o teste JVM e o fluxo de assinatura com `META-INF/CODELOVE.txt`, seguido por exportação e inspeção de um APK real.\n5. **Regressão final:** validar os fluxos restantes de edição, SAF, imagens, sons, entradas, salvamento e falhas de exportação nos layouts vertical e horizontal aplicáveis. A cobertura em múltiplos dispositivos/Android permanece limitada.\n6. **Preparação da release:** revisar documentação, licenças de terceiros, identidade e assinatura da distribuição, validar artefatos finais e preparar a primeira publicação no GitHub.

A exportação **AAB para lojas** é uma evolução futura, não um pré-requisito para a primeira versão distribuída via GitHub. O workflow pode gerar AABs em ambiente CI, mas **o exportador no dispositivo atualmente gera APKs, não AABs**.

## Regras de manutenção

- Preservar a arquitetura offline e o acesso direto ao projeto via SAF.
- Não adicionar hospedagem, compilação remota nem dependência de contas externas.
- Não trocar o runtime LÖVE em função de um aviso de versão emitido por um jogo antigo; acompanhar atualizações do motor como tarefa separada, com testes de compatibilidade.
- Restaurar a implementação funcional anterior quando uma tentativa de correção falhar, antes de testar alternativa.
- **Não iniciar GitHub Actions sem autorização explícita**.
