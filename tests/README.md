# Bateria de testes para o projeto BlockchainIST

## Estrutura dos testes
```
initial-code/tests/
├── inputs
│   ├── input01.txt
│   ├── input02.txt
│   ├── input03.txt
│   └── input04.txt
├── outputs
│   ├── out01.txt
│   ├── out02.txt
│   ├── out03.txt
│   └── out04.txt
├── README.md
└── run_tests.sh
```

## Antes de começar

Cada teste corresponde a um ficheiro `inputs/input*.txt` cujo conteúdo é uma sequência de comandos que o cliente deve executar.
Para cada ficheiro `inputs/input*.txt`, existe um ficheiro `outputs/out*.txt` com o *output* que se espera observar na consola do cliente.

Uma bateria de testes começa pelo `input01.txt` e avança pelos testes com índices consecutivos (`input01.txt`, `input02.txt`, `input03.txt`, ...). 
A bateria termina assim que o teste do próximo índice não existir.

O *shell script* que executa a bateria de testes lançará apenas o processo do cliente (uma nova instância do cliente é lançada para cada teste). 


## Como usar?

1. Editar *shell script* `run_tests.sh` e confirmar que as variáveis na secção *PATHS* estão de acordo com a estrutura do seu projeto.

2. Compilar todas as componentes do projeto.
   Sugestão: `mvn clean install -DskipTests -f ../pom.xml` (assumindo que está na diretoria `tests`).

3. Lançar o(s) nó(s).

4. (entregas A.2 e seguintes) Lançar sequenciador.

5. Finalmente, executar `run_tests.sh`. Este *script* lançará o cliente Java, assumindo que o *goal* `exec:java` (configurado no `pom.xml` do cliente) já passa os argumentos de linha de comando necessários.

6. Observar quais testes falharam. Para esses, consultar os ficheiros na diretoria `test-outputs/` 
(que contêm os *outputs* gerados em cada teste) e compará-los com os ficheiros respetivos na diretoria `outputs/`.

## Descrição dos Testes

- **input01.txt**: Teste básico de criação e eliminação de uma carteira.
- **input02.txt**: Teste básico de criação e eliminação de duas carteiras. 
- **input03.txt**: Teste básico de transferências entre carteiras.
- **input04.txt**: Teste de transferência de uma carteira sem saldo suficiente.

## Perguntas frequentes

- *Podemos inventar novos testes e adicioná-los à bateria?* 
Sim, recomendamos que o façam! 
A bateria de testes que fornecemos é intencionalmente curta. Devem estendê-la com outros testes que achem relevantes.

- *Os nós/sequenciador são terminados e lançados de novo a cada teste?* Não. Os testes assumem que os nós/sequenciador se mantêm em execução durante a bateria inteira de testes. Devido à natureza deste projeto, não é possível limpar completamente o estado dos nós/sequenciador, por isso, para cada teste, o *script* cria um identificador único (*timestamp*) e gera uma bateria de testes específica para cada execução do *script*. Assim, cada teste é isolado (suficientemente) dos outros. Para que o *script* consiga gerar a bateria de testes específica, é necessário que o *input* dos testes contenha o marcardor `XXXX` nos identificadores das carteiras, que será automaticamente substituído.

- *O nosso projeto será avaliado exclusivamente com testes automáticos como estes?* 
Não. A avaliação do código dos projetos não é automática. 
No entanto, usaremos alguns testes como aqueles que aqui fornecemos para 
*guiar* a análise manual que os docentes farão do código submetido por cada grupo.