# CodeLove 1.0.0 — checklist de lançamento

Estado: em preparação, ainda **não lançado**. A auditoria foi integrada à branch funcional `feature/sora-editor`. A `main` permanece atrás dela e preserva a apresentação inglesa anterior até a integração final.

## Validações concluídas

- [x] Compilar as variantes Android Debug e Release — workflow `build` #44665273830.
- [x] Testar exportação offline, manifesto, ícone, alinhamento e assinatura dos jogos — `offline-apk-prototype` #44665273811.
- [x] Separar o runtime exportado das classes e recursos do editor — workflow #44633701383 e StarGuard real.
- [x] Inserir e validar carimbo interno `META-INF/CODELOVE.txt` — workflow #44665273811 e StarGuard real.
- [x] Testar no Android atualização de um jogo sem desinstalação, com `versionCode` crescente e mesmo certificado.
- [x] Testar backup/restauração de uma chave `.p12` e preservação do certificado após exportação.

- [x] Recompilar o pacote de código-fonte extraído e validar bibliotecas LGPL modificadas (builds #554 e #555; teste Android aprovado pelo mantenedor).
- [x] Validar interface de licenças offline (build #557, teste manual aprovado).

## Antes de publicar no GitHub

- [x] Integrar a auditoria experimental à branch funcional (build #559, autorizada).
- [x] Validar a compilação integrada: #559, #560 e #561 aprovadas; teste de chaves #4 e protótipo offline #19 aprovados.
- [ ] Validação complementar não bloqueante: export-smoke-test #22 falhou antes da compilação na preparação de assets; correção incorporada, ainda não executada. Não confundir com o exportador offline real, validado no workflow principal e no Android.

- [x] Executar a compilação com a verificação independente da assinatura do APK **CodeLove Release** (build #559, etapa de assinatura aprovada).
- [ ] Verificar `apksigner verify` e o certificado do APK destinado ao público; manter backup seguro da chave de release e de sua senha. **Nunca publicar com a chave de debug**.
- [x] Definir os termos das contribuições próprias: GPL-3.0-only para editor e ferramentas, zlib para o runtime independente. Ver `LICENSING.md`.
- [ ] Confirmar a delimitação de todos os arquivos e componentes distribuídos e a disponibilidade do código-fonte correspondente.
- [x] Concluir a auditoria técnica de avisos e substituição de bibliotecas LGPL para o inventário revisado (builds #555–558). Não constitui certificação jurídica; publicar o código correspondente permanece obrigatório conforme os termos aplicáveis.
- [x] Revisar o suplemento dos JARs Guava ListenableFuture 1.0 e JetBrains Annotations 13.0 e verificar o APK Release #558 e seu runtime.
- [ ] Anexar o pacote de código-fonte correspondente, SHA256SUMS e inventário à mesma GitHub Release do APK assinado; não usar artefatos temporários do Actions como distribuição permanente.
- [ ] Fazer regressão manual da interface nos layouts vertical e horizontal, em celular e tablet: SAF, troca de pasta principal, abas, edição, salvamento, console, execução, exportação e falhas/cancelamentos.
- [ ] Validar a instalação limpa e a atualização do próprio CodeLove com o APK Release, sem misturar a assinatura pública com a de debug usada nos testes.
- [x] Revisar README em inglês, créditos, versão 1.0.0, instalação e documentação de recompilação.
- [ ] Confirmar screenshots atuais se forem adicionadas (nenhuma imagem antiga reinserida).
- [ ] Validar a alteração de CI que torna AABs opcionais e registra a impressão digital do certificado; não altera o aplicativo.
- [ ] Integrar a versão testada à `main`, preservando o histórico da `main` e das alterações aprovadas (sem aplicar árvores antigas de experimentos).
- [ ] Excluir as seis branches de trabalho após verificar a integração e registrar os SHAs dos experimentos não incorporados.
- [ ] Criar a tag `v1.0.0` para o commit exato do APK e pacote de fontes publicados; publicar a primeira GitHub Release. Essas operações exigem autorização no momento adequado.

## Fora do escopo desta primeira release

- Publicação paga na Play Store: planejada para uma etapa posterior, com análise própria de requisitos de loja, formato AAB, testes e políticas.
- Exportação de AAB pelo CodeLove no dispositivo: não faz parte da 1.0.0.
- Hospedagem de projetos ou compilação de jogos em servidor: não prevista.

A aprovação automatizada não substitui o teste manual em Android nem garante compatibilidade com todos os jogos e dispositivos.
