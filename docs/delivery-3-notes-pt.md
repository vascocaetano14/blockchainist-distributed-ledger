# DOCUMENTO QUE ACOMPANHA A 3ª ENTREGA DO PROJETO DE SD

1) Quais dos seguintes requisitos foram corretamente resolvidos?
Abaixo de cada requisito, respondam: "Sim" ou "Não" ou "Sim mas com erros: [enumerar sucintamente os erros]"

- Suporta a execução/resposta a pedidos de transferência antes da transação correspondente ser entregue pelo sequenciador [C.1].
Sim, para o caso indicado abaixo.

- As transações são assinadas digitalmente pelo utilizador que as invocou e as respetivas assinaturas são verificadas antes de cada transação ser executada nas réplicas [C.2].
Sim.

- A blockchain gerada pelo sequenciador é assinada digitalmente e as assinaturas correspondentes são verificadas pelos nós que recebem cada bloco [C.2].
Sim.

2) Descrevam sucintamente as principais alterações que o grupo aplicou a cada componente indicada abaixo (máx. 800 palavras)
Idealmente, componham uma lista de itens (cada um iniciado por um hífen, tal como a listagem dos requisitos apresentada acima).
Refiram ainda se houve alterações aos argumentos de linha de comando dos executáveis, quais foram e qual a sua justificação.

Aos .proto:
-common.proto: Definição de nova estrutura mensagem para assinaturas, com signatureId e signatureValue. 
Adição de instâncias assinatura à estrutura de mensagens request de transações (create, delete, transfer).

-Node-sequencer.proto: Definição de nova estrutura SignedDeliverBlockResponse que encapsula a repsosta anterior DeliverBlockResponse e uma nova assinatura.
Alteração da mensagem de retorno do serviço DeliverBlock para a nova estrutura SignedDeliverBlockResponse.

Ao programa do Cliente:
- ClientMain.java: O cliente passou a aceitar a propriedade -Duser.id para se autenticar como um utilizador específico. Quando definida, carrega a chave privada correspondente dos resources usando CryptoUtils.readPrivateKey.
- CommandProcessor.java: Adicionámos o método buildTransactionSignature() que reconstrói a Transaction protobuf sem o campo de assinatura, serializa com toByteArray() e assina com CryptoUtils.sign usando SHA256withRSA. O resultado depois é encapsulado numa Signature protobuf com signerIdentifier e signatureValue. 
- ClientNodeService.java: A assinatura passou a ir dentro dos Requests protobuf como campo Signature nos CreateWalletRequest, DeleteWalletRequest e TransferRequest
- Os métodos WithTimeout e Async passaram a receber Signature txSignature como parâmetro e a incluí-la no request com setSignature()

Ao programa do Nó:
- MembershipTable.java: Adicionámos o método getAllUserIds() que retorna o conjunto de todos os userId conhecidos, necessário para o carregamento das chaves públicas
- NodeServiceImpl.java: Implementamos o mecanismo de execução especulativa. Agora esta classe tem como atributo privado um Set de chaves de transações que grava que transações foram executadas especulativamente. 
Em transfer ao verificar os cenários em que não poderá executar especulativamente, executa especulativamente e grava tal no Set. 
Quando aplica o bloco que contém a transferência, retornado do sequenciador, remove-a do Set de execução especulativa, dando toggle off a este estado temporário.
Os métodos deleteWallet e transferWallet salvaguardam agora a lógica de eliminação pendente. 
O método delete adiciona à wallet respetiva a flag que assinala o estado temporário de eliminação pendente e o método transfer verifica que nenhuma das wallets envolvidas está pendente de eliminação antes de executar.
O construtor passou a carregar as chaves públicas de todos os utilizadores conhecidos com MembershipTable.getAllUserIds() e CryptoUtils.readPublicKey e a chave pública do sequenciador na inicialização.
Adicionámos método validateUserBelongsToNodeOrganization que vai ser usado para verificar a partir de userId presente em BroadcastRequest se user pertence a organização especificada no programa. 
Adicionámos o método verifyBlockSignature() que verifica a assinatura de blocos do sequenciador e adicionámos o método verifyTransactionSignature() que verifica a assinatura da transação do cliente.

- NodeState.java: Implementamos a função que executa especulativamente uma transferência sobre o estado do nó.
Criámos uma estrutura pendingDeletions em NodeState que permite declarar um estado temporário de eliminação pendente a cada carteira. Criamos também funções auxiliares que permitem a manipulação deste Set.


Ao programa do Sequenciador:
-SequencerServiceImpl.java: Sequenciador agora passa a identificar as transferências para assim devolver um earlyAck mais cedo, flag necessária para o mecanismo de execução especulativa.
Sequenciador agora possuí chave privada como atributo e obtém-na no seu construtor ao lê-la a partir de Seq.priv em resources.
DeliverBlock agora opera com SignedDeliverBlockResponse. Cria uma nova instância signature que popula com a bytestream obtida depois de assinar o DeliverBlockResponse ,que já construia anteriormente, com a sua chave privada.
Constroi agora nova mensagem de resposta cápsula da anterior com a assinatura.

-SequencerState.java: Atualizar classe para albergar novo parâmetro earlyAck.

3) Na vossa solução para o requisito da C.1, em quais condições uma transferência **NÃO** é executada pelo nó (que recebeu o pedido respetivo) antes da transação ser enviada ao sequenciador? (máx. 100 palavras)
Caso 1: Carteira cA é eliminada sucedidamente, posteriormente é feita uma Transferência 1 entre cB e cA por 10 unidades de criptomoeda.
A transferência 1 não será executada pelo nó como já era previsto por ordem total, no entanto agora prevêmos ordem causal nesta linha de operações.
O nó poderá confirmar ao cliente o insucesso da operação de transferência mais cedo. Salvando tempo de execução face à espera tradicional pelo broadcastResponse da operação eliminar carteira vinda do sequenciador.


4) Como foi frisado na primeira aula teórica, não é aceitável o uso de GenAI/Code Copilots para gerar código usado diretamente no projeto.
Tendo isto em conta, respondam brevemente às seguintes 3 perguntas:

i) Os membros do grupo conseguem explicar o código submetido se tal for perguntado na discussão do projeto (feita sem recurso a AI)?
Sim.

ii) Os membros do grupo conseguem defender todas as decisões de desenho e implementação se tal for perguntado na discussão do projeto (feita sem recurso a AI)?
Sim.

iii) Usaram alguma(s) ferramenta(s) de AI? Se sim, para que uso?
Como na entrega anterior foram usadas ferramentas de AI, meramente como meio de pesquisa de informação sobre funções referentes a novas bibliotecas a usar nesta entrega, como por exemplo as demonstradas no laboratório de criptografia. 
Foi também usada AI com o intuito de fazer uma divisão eficiente das tarefas, tanto por aspetos de divisão de carga, entre os vários elementos do grupo, como para efeitos de maximizar os requisitos implementados pela totalidade do grupo para esta entrega.


