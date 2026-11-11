# DOCUMENTO QUE ACOMPANHA A 2ª ENTREGA DO PROJETO DE SD

1) Quais dos seguintes requisitos foram corretamente resolvidos?
Abaixo de cada requisito, respondam: "Sim" ou "Não" ou "Sim mas com erros: [enumerar sucintamente os erros]"

- Estender o sistema para permitir nós replicados
- Sim, mas com erros: Implementamos suporte do lançamento de múltiplos nós, mas eles não sincronizam automaticamente o estado inicial.

- Difusão baseada em blocos (e não transações individuais)
- Sim: O sequenciador agrupa transações em blocos.

- Variantes não-bloqueantes dos comandos
- Sim: Criamos métodos assíncronos em `ClientNodeService` e complementamos `CommandProcessor` com a correspondente lógica de comandos.

- Funcionalidade de atrasar, no nó, a execução de cada pedido
- Sim: implementámos metadados gRPC (`DELAY_KEY`) e `NodeDelayInterceptor` que executam a  `Thread.sleep()`.

- Suporte ao lançamento de novos nós a qualquer ponto no tempo
- Sim, mas com erros: O nó pode ser lançado e sincronizar via pull de blocos do sequenciador, mas não há mecanismo explícito de "catch-up" - simplesmente começa do bloco 1.

- Tolerância a falhas silenciosas dos nós
- Não: não conseguimos fazer o retry automático do cliente em caso de falha de nó, nem o mecanismo de failover,


2) Descrevam sucintamente as principais alterações que o grupo aplicou a cada componente indicada abaixo (máx. 800 palavras)
Idealmente, componham uma lista de itens (cada um iniciado por um hífen, tal como a listagem dos requisitos apresentada acima).
Refiram ainda se houve alterações aos argumentos de linha de comando dos executáveis, quais foram e qual a sua justificação.

Aos .proto:
- Adicionado campo `request_id` na `BroadcastRequest` para identificar transações e permitir deduplicação.
- Adicionado tipo `Block` com `block_number` e `repeated transactions` para suportar difusão atómica baseada em blocos.
- Adicionado campo boolean `deduplicated` na `BroadcastResponse` para indicar se a transação já foi entregue anteriormente.
- Novo serviço `SequencerService` com RPC `Broadcast()` e `DeliverBlock()` em lugar da interface simplificada anterior.
- Criados objetos de resposta vazios (`CreateWalletResponse`, `DeleteWalletResponse`, `TransferResponse`) em conformidade com padrão gRPC.

Ao programa do Cliente:
- Implementámos as versões não-bloqueantes dos comandos c/e/t que usam `asyncStub` e `ResponseObserver` para processamento assíncrono.
- Adicionámos uma thread pool `ResponseObserver' para as respostas assincrónas.
- Implementámos um sistema de `pendingCount` para aguardar conclusão de todas as operações assincrónas antes de fechar.
- Conseguimos tratar dos metadados gRPC para propagação de `node_delay` e `request_id` aos nós.

Ao programa do Nó:
- Adicionamos `pullBlocksLoop()` como worker thread (daemon) que continuamente solicita blocos do sequenciador e os aplica.
- Implementado `NodeState` com rastreamento de `lastAppliedBlockNum` para sincronização de blocos.
- Adicionado mapa `outcomesByBlock` que armazena erros de cada transação por bloco para retorno ao cliente.
- Implementado `NodeDelayInterceptor` que extrai `node_delay` dos metadados gRPC e executa atraso.
- Classe `Transaction` de domínio para separação gRPC/domínio.
- Argumentos de linha de comando estendidos: `<port> <organization> <sequencer_host>:<sequencer_port> [-debug]`.

Ao programa do Sequenciador:
- Implementado `SequencerState` com gestão de blocos em lista (`List<List<SequencerTransaction>> blocks`).
- Adicionado timer com thread daemon que fecha blocos após `T` segundos OU quando atinge `N` transações (configuráveis via argumentos).
- Implementado mapa `requestToBlock` para deduplicação: mapeia `request_id` ao bloco onde foi colocada.
- Implementados RPC `Broadcast()` e `DeliverBlock()` com sincronização robusta via `synchronized` e `wait/notifyAll()`.
- Argumentos de linha de comando estendidos: `<port> [N] [T] [-debug]` (N=tamanho máximo bloco, T=timeout segundos)

---

## 3. Identificador de Transação no Sequenciador

Sim, as transações recebidas pelo sequenciador levam um identificador.

Formato e geração:
- O identificador é um campo `request_id` na `BroadcastRequest`.
- Geração: O cliente deveria gerar um UUID ou ID único por comando, porém não conseguimos implementar no código atual corretamente. O identificador é recebido via metadados gRPC com `"request_id"`. O cliente gereria um UUID para cada transação e o passe nos metadados antes de invocar as operações bloqueantes/assincronas. Porém, queremos dizer que sabemos que o propósito seria permitir que o sequenciador impedisse transações duplicadas (reenvios) e retornar imediatamente o bloco onde a transação já foi colocada, garantindo a idempotência.


4) Como foi frisado na primeira aula teórica, não é aceitável o uso de GenAI/Code Copilots para gerar código usado diretamente no projeto.
Tendo isto em conta, respondam brevemente às seguintes 3 perguntas:

i) Os membros do grupo conseguem explicar o código submetido se tal for perguntado na discussão do projeto (feita sem recurso a AI)?
- Sim.

ii) Os membros do grupo conseguem defender todas as decisões de desenho e implementação se tal for perguntado na discussão do projeto (feita sem recurso a AI)?
- Sim.

iii) Usaram alguma(s) ferramenta(s) de AI? Se sim, para que uso?

- Sim, usamos inteligência artificial, primeiramente com o intuito de procurar e agregar informação sobre ferramentas de syntax de java, como bibliotecas nativas, que nos pudessem ser úteis ao desenvolvimento de certos requisitos do projeto. Foi também usada inteligência artificial no sentido de organizar linhas de raciocínio sobre ordem de implementações a tomar na totalidade de entregas. Por fim foi também usada inteligência artificial para tentar dividir o trabalho de forma equitativa entre os 3 membros, por forma a cada um tentar sempre que possível fazer trabalho auónomo e independente nas três vertentes: cliente, node e sequencer(de qualquer maneira as linhas de trabalho independentes cruzaram-se frequentemente ao longo do desenvolvimento do projeto e cada membro trabalhou em todas as vertentes).
