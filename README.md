<p align="center">
  <img src="assets/icon-codelove.svg" width="136" alt="Ícone do CodeLove" />
</p>

# CodeLove

**CodeLove** é um ambiente Android para editar código Lua e executar projetos com o mecanismo [LÖVE](https://love2d.org/).

O projeto combina uma interface Android em Java com o Sora Editor e o runtime nativo do LÖVE. O objetivo é permitir que o usuário abra uma pasta existente, trabalhe diretamente nos arquivos dela e execute o projeto no próprio dispositivo.

## Estado do projeto

O fluxo principal de trabalho já está implementado:

- Seleção de uma pasta pelo Storage Access Framework (SAF).
- Acesso direto aos arquivos da pasta selecionada, com a autorização concedida pelo Android.
- Criação, leitura, edição, renomeação e exclusão de arquivos e pastas.
- Salvamento explícito pelo editor.
- Atualização da árvore ao detectar alterações feitas fora do CodeLove.
- Execução de projetos Lua por meio do runtime LÖVE.

**A pasta selecionada pelo usuário é a fonte de verdade.** O projeto não mantém uma cópia de trabalho persistente nem um mecanismo de sincronização entre duas pastas.

## Tecnologias

- Android: Java.
- Interface: Views nativas do Android.
- Editor: Sora Editor 0.24.7, com suporte a Lua e funções LÖVE.
- Runtime de jogos: LÖVE.
- Código nativo: C/C++ com CMake e Android NDK.
- Build: Gradle.
- Integração contínua: GitHub Actions.

O runtime nativo é mantido por submódulos Git. Não remova nem substitua os submódulos sem verificar a compatibilidade com o projeto.

## Estrutura do repositório

```text
.
├── .github/workflows/       # Compilação automatizada
├── app/
│   ├── src/main/java/       # Editor, workspace e acesso SAF
│   ├── src/main/cpp/        # Integração nativa e CMake
│   ├── src/main/res/        # Recursos compartilhados
│   ├── src/normal/          # Aplicativo CodeLove
│   └── src/embed/           # Variante que incorpora um jogo
├── gradle/wrapper/          # Versão fixada do Gradle
├── app/build.gradle         # Configuração Android e dependências
├── build.gradle             # Configuração Gradle raiz
└── settings.gradle          # Módulos do projeto
```

### Componentes principais

| Arquivo | Responsabilidade |
|---|---|
| `GameActivity.java` | Integração entre Android e runtime LÖVE |
| `Workspace.java` | Interface e coordenação do ambiente de trabalho |
| `CodeEditor.java` | Sora Editor, abas de arquivos e salvamento manual |
| `LuaSyntax.java` | Reconhecimento léxico usado no destaque de sintaxe Lua |
| `OverlayUi.java` | Componentes visuais do workspace |
| `SafProjectManager.java` | Seleção e gerenciamento da pasta concedida pelo SAF |
| `DirectSafStorage.java` | Operações diretas sobre documentos e diretórios SAF |
| `FileOps.java` | Operações auxiliares com arquivos locais |

## Preparar o ambiente

Requisitos usados pela configuração atual:

- JDK 17.
- Android SDK com plataforma de compilação configurada no Gradle.
- Android NDK `27.3.13750724`.
- CMake 3.21 ou superior.
- Python 3.9 ou superior para a conversão de módulos nativos que ainda usam `Android.mk`.

Clone o repositório incluindo os submódulos:

```bash
git clone --recurse-submodules https://github.com/joa-no/CodeLove.git
cd CodeLove
```

Se o repositório já foi clonado sem os submódulos:

```bash
git submodule update --init --recursive
```

## Compilar

APK de depuração:

```bash
./gradlew assembleNormalRecordDebug
```

APK de release (assinado no GitHub Actions quando os segredos de assinatura estão configurados; localmente permanece sem assinatura de distribuição se as variáveis de ambiente não forem fornecidas):

```bash
./gradlew assembleNormalRecordRelease
```

Os arquivos gerados ficam em `app/build/outputs/`. A automação do GitHub Actions também compila as variantes Debug e Release e publica os artefatos de cada execução.

No Windows, use `gradlew.bat` no lugar de `./gradlew`.

## Assinatura de depuração

O repositório contém `app/codelove-debug.p12`, uma chave de assinatura **de depuração, versionada intencionalmente e exclusiva para testes**. Ela permite que instalações de teste sucessivas mantenham a mesma identidade de assinatura.

Essa chave não deve ser usada para publicar o aplicativo. Uma distribuição real precisa de uma chave de release privada, armazenada fora do repositório e protegida por segredos do ambiente de build.

## Diretrizes de manutenção

- Preserve o acesso direto via SAF. Não reintroduza cópia de trabalho persistente ou sincronização sem uma necessidade técnica demonstrada.
- Não altere o fluxo de salvamento explícito sem testar a proteção contra perda de alterações.
- Preserve os submódulos nativos e seus avisos de licença.
- Não adicione dependências sem uso comprovado.
- Faça mudanças pequenas e compile Debug e Release antes de considerar uma alteração concluída.
- Testes automatizados não substituem a validação no Android real, especialmente para SAF, permissões e ciclo de vida.

## Licenças

CodeLove contém código próprio e componentes derivados ou incorporados do projeto LÖVE e de suas dependências. Consulte os avisos em `license.txt`, `app/src/normal/assets/codelove-licenses.txt` e nos respectivos submódulos. Os créditos e licenças originais devem ser preservados.
