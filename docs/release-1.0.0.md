# CodeLove 1.0.0 — checklist de lançamento

Estado: em preparação, ainda **não lançado**. A branch de trabalho é `feature/sora-editor`; o branch padrão `main` permanece atrás dela até a integração revisada.

## Validações concluídas

- [x] Compilar as variantes Android Debug e Release — workflow `build` #44665273830.
- [x] Testar exportação offline, manifesto, ícone, alinhamento e assinatura dos jogos — `offline-apk-prototype` #44665273811.
- [x] Separar o runtime exportado das classes e recursos do editor — workflow #44633701383 e StarGuard real.
- [x] Inserir e validar carimbo interno `META-INF/CODELOVE.txt` — workflow #44665273811 e StarGuard real.
- [x] Testar no Android atualização de um jogo sem desinstalação, com `versionCode` crescente e mesmo certificado.
- [x] Testar backup/restauração de uma chave `.p12` e preservação do certificado após exportação.

## Antes de publicar no GitHub

- [ ] Executar a compilação com a nova verificação independente da assinatura do APK **CodeLove Release**, introduzida após o workflow #44665273830 (não executá-la sem autorização).
- [ ] Verificar `apksigner verify` e o certificado do APK destinado ao público; manter backup seguro da chave de release e de sua senha. **Nunca publicar com a chave de debug**.
- [x] Definir os termos das contribuições próprias: GPL-3.0-only para editor e ferramentas, zlib para o runtime independente. Ver `LICENSING.md`.
- [ ] Confirmar a delimitação de todos os arquivos e componentes distribuídos e a disponibilidade do código-fonte correspondente.
- [ ] Revisar obrigações de todas as dependências, em particular a LGPL-2.1 do Sora Editor 0.24.7 (avisos, código-fonte disponível e condições aplicáveis à substituição/religação da biblioteca), com atenção a futuras opções proprietárias. A inclusão do aviso não substitui a avaliação de conformidade.
- [ ] Confirmar os avisos de licença de outras dependências distribuídas (AndroidX, Bouncy Castle, apksig, Oboe e componentes nativos).
- [ ] Fazer regressão manual da interface nos layouts vertical e horizontal, em celular e tablet: SAF, troca de pasta principal, abas, edição, salvamento, console, execução, exportação e falhas/cancelamentos.
- [ ] Validar a instalação limpa e a atualização do próprio CodeLove com o APK Release, sem misturar a assinatura pública com a de debug usada nos testes.
- [ ] Revisar README, screenshots, créditos, versão e instruções de instalação.
- [ ] Integrar a branch testada ao `main` sem reintroduzir tentativas anteriores descartadas, criar a tag `v1.0.0` e publicar a primeira GitHub Release com APK e notas. Essas operações exigem autorização no momento adequado.

## Fora do escopo desta primeira release

- Publicação paga na Play Store: planejada para uma etapa posterior, com análise própria de requisitos de loja, formato AAB, testes e políticas.
- Exportação de AAB pelo CodeLove no dispositivo: não faz parte da 1.0.0.
- Hospedagem de projetos ou compilação de jogos em servidor: não prevista.

A aprovação automatizada não substitui o teste manual em Android nem garante compatibilidade com todos os jogos e dispositivos.
