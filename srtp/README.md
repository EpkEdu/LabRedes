# SRTP — Simple Reliable Transport Protocol

**Trabalho Final — Laboratório de Redes de Computadores**  
Pontifícia Universidade Católica do Rio Grande do Sul — Escola Politécnica

---

## Descrição

Implementação do protocolo SRTP sobre UDP em Java. O protocolo transfere arquivos de forma confiável entre dois hosts, implementando:

- **Parte 1:** Stop-and-Wait (SAW) com CRC32, handshake e teardown
- **Parte 2:** Go-Back-N (GBN) e Selective Repeat (SR) *(a implementar)*

---

## Requisitos

- Java 17 ou superior (JRE/JDK)
- Verificar versão: `java -version`

---

## Compilação

```bash
# Cria diretório de saída e compila
mkdir -p out
javac -encoding UTF-8 -d out src/srtp/*.java

# Empacota o JAR executável
jar cfm srtp.jar MANIFEST.MF -C out .
```

Ou use o script incluso:
```bash
./build.sh
```

---

## Execução

### Receiver (modo listen)

```bash
java -jar srtp.jar --listen --port <P> [--output <arquivo>] [--window <N>] [--mode <modo>]
```

### Sender (modo connect)

```bash
java -jar srtp.jar --host <host> --port <P> --file <arquivo> [--window <N>] [--mode <modo>]
```

---

## Argumentos de Linha de Comando

| Argumento          | Obrigatório | Descrição                                              |
|--------------------|-------------|--------------------------------------------------------|
| `--listen`         | Receiver    | Ativa modo receiver (escuta na porta P)                |
| `--port <P>`       | Ambos       | Porta base (1–65534). Receiver escuta em P; sender envia para P e recebe ACKs em P+1 |
| `--host <host>`    | Sender      | Endereço IP ou hostname do receiver                    |
| `--file <arquivo>` | Sender      | Caminho do arquivo a transferir                        |
| `--output <arq>`   | Receiver    | Arquivo de saída (padrão: `received_output.bin`)       |
| `--window <N>`     | Opcional    | Tamanho de janela proposto: 1–255 (padrão: 1)          |
| `--mode <modo>`    | Opcional    | Protocolo: `saw` \| `gbn` \| `sr` (padrão: `saw`)     |

---

## Exemplos de Uso

### Stop-and-Wait (Parte 1)

```bash
# Terminal 1 — Receiver escutando na porta 6000
java -jar srtp.jar --listen --port 6000 --output recebido.bin

# Terminal 2 — Sender (envia para 6000, escuta ACKs em 6001)
java -jar srtp.jar --host 192.168.1.10 --port 6000 --file dados.bin
```

### Verificação de integridade do arquivo recebido

```bash
md5sum dados.bin recebido.bin
```

---

## Modelo de Portas

O protocolo usa um par de portas derivado de um único parâmetro **P**:

```
Receiver  ←──── dados ────── Sender
  :P                          :P+1

Receiver  ──── ACK/NACK ────► Sender
  :P                          :P+1
```

- **Receiver** escuta em `P` para todos os pacotes (SYN, dados, FIN)
- **Sender** conecta ao receiver em `P` e, após o handshake, escuta ACKs em `P+1`
- **Receiver** envia ACKs/NACKs para `sender_IP:(P+1)`

---

## Formato do Cabeçalho (9 bytes)

```
 0               1               2
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|S|F|    SEQ (14 bits)        |A|N| ACK (14 bits)|
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  Length (8 bits)  |        CRC32 (32 bits)     |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

| Campo    | Bits | Descrição                                        |
|----------|------|--------------------------------------------------|
| SYN (S)  | 1    | Flag de estabelecimento de conexão               |
| FIN (F)  | 1    | Flag de encerramento de conexão                  |
| SEQ      | 14   | Número de sequência (0–16383, wrap-around)       |
| ACK flag | 1    | Indica que o campo ACK é válido                  |
| NACK (N) | 1    | Negative acknowledgement                         |
| ACK      | 14   | Número de acknowledgement                        |
| Length   | 8    | Bytes de payload; 255=intermediário, <255=último |
| CRC32    | 32   | Checksum sobre cabeçalho (CRC=0) + payload       |

---

## Estrutura do Projeto

```
srtp/
├── src/
│   └── srtp/
│       ├── Main.java        # Ponto de entrada + parse de argumentos CLI
│       ├── RTPPacket.java   # Estrutura do pacote, serialização e CRC32
│       ├── RTPSocket.java   # Wrapper UDP para envio/recepção de pacotes RTP
│       ├── Sender.java      # Lógica do sender (handshake + SAW + teardown)
│       └── Receiver.java    # Lógica do receiver (handshake + SAW + teardown)
├── MANIFEST.MF
├── build.sh
└── README.md
```

---

## Comportamentos Conformes à Especificação

- **CRC32:** Calculado sobre o cabeçalho completo (campo CRC zerado) concatenado com payload. Pacotes com CRC inválido são **descartados silenciosamente** (sem NACK).
- **Sequência:** Contada em pacotes (não bytes), iniciando em 0, com wrap-around em 16383.
- **Length=255:** Pacote intermediário — receiver bufferiza e aguarda.  
  **Length<255:** Último pacote do stream — receiver faz *push* do buffer.  
  **Length=0:** Edge case — arquivo múltiplo de 255 bytes, fim sem payload residual.
- **Handshake:** Three-way (SYN → SYN+ACK → ACK). SEQ e ACK são 0.
- **Teardown:** Two-way (FIN → FIN+ACK). Iniciado pelo sender após confirmação do último pacote.
- **Timeout:** 100ms fixo. Retransmissão automática.
- **Fora de ordem (SAW):** Descartados silenciosamente; reenvia ACK do último pacote aceito.

---

## Cenários de Teste (Parte 1)

Use o **Clumsy** (Windows) ou **tc netem** (Linux) no sender ou receiver:

### Latência (sem perda)

| Cenário | Latência |
|---------|----------|
| L0      | 0ms (baseline) |
| L1      | 50ms |
| L2      | 100ms |
| L3      | 150ms |

```bash
# Linux — adiciona 50ms de latência na interface de saída
sudo tc qdisc add dev eth0 root netem delay 50ms

# Remove
sudo tc qdisc del dev eth0 root
```

### Perda de pacotes (sem latência)

| Cenário | Perda |
|---------|-------|
| P0      | 0% (baseline) |
| P1      | 1% |
| P2      | 5% |
| P3      | 10% |
| P4      | 25% |

```bash
# Linux — 5% de perda
sudo tc qdisc add dev eth0 root netem loss 5%
```

---

## Diretório de Capturas

Coloque os arquivos `.pcapng` em `capturas/`, nomeados conforme o cenário:

```
capturas/
├── saw_L0.pcapng
├── saw_L1.pcapng
├── saw_L2.pcapng
├── saw_L3.pcapng
├── saw_P0.pcapng
├── saw_P1.pcapng
├── saw_P2.pcapng
├── saw_P3.pcapng
└── saw_P4.pcapng
```
