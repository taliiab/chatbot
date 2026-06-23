from typing import Any, Text, Dict, List
import datetime
import psycopg2
from rasa_sdk import Action, Tracker
from rasa_sdk.events import SlotSet, AllSlotsReset
from rasa_sdk.events import FollowupAction
from rasa_sdk.executor import CollectingDispatcher

def obter_conexao():
    return psycopg2.connect(
        dbname="rasa", user="postgres", password="admin",
        host="postgres", port="5432"
    )

def buscar_config():
    conn = None
    cur = None

    try:
        conn = obter_conexao()
        cur = conn.cursor()

        cur.execute("""
            SELECT chave, valor
            FROM configuracoes
        """)

        dados = cur.fetchall()

        return {k: v for k, v in dados}

    except Exception as e:
        print(f"Erro config: {e}")
        return {}

    finally:
        if cur: cur.close()
        if conn: conn.close()

def buscar_produto(nome_produto: str):
    conn = None
    cur = None

    try:
        conn = obter_conexao()
        cur = conn.cursor()

        cur.execute("""
            SELECT id, preco
            FROM produtos
            WHERE LOWER(nome) = LOWER(%s)
            AND ativo = true
            LIMIT 1
        """, (nome_produto,))

        return cur.fetchone()

    except Exception as e:
        print(f"Erro produto: {e}")
        return None

    finally:
        if cur: cur.close()
        if conn: conn.close()

class ActionVerificarCliente(Action):

    def name(self):
        return "action_verificar_cliente"

    def run(self, dispatcher, tracker, domain):

        id_whatsapp = str(tracker.sender_id)

        conn = obter_conexao()
        cur = conn.cursor()

        cur.execute("""
            SELECT nome
            FROM clientes
            WHERE id_whatsapp = %s
        """, (id_whatsapp,))

        cliente = cur.fetchone()

        cur.close()
        conn.close()

    
        if cliente:
            return [
                SlotSet("nome_cliente", nome_do_banco)
            ]


        dispatcher.utter_message(
            text="Antes de começarmos, qual é o seu nome?"
        )

        return []


class ActionSalvarNomeCliente(Action):

    def name(self):
        return "action_salvar_nome_cliente"

    def run(self, dispatcher, tracker, domain):

        nome = tracker.get_slot("nome_cliente")
        id_whatsapp = str(tracker.sender_id)

        if not nome:
            nome = tracker.latest_message.get("text")

        conn = obter_conexao()
        cur = conn.cursor()

        cur.execute("""
            INSERT INTO clientes (id_whatsapp, nome)
            VALUES (%s, %s)
            ON CONFLICT (id_whatsapp)
            DO UPDATE SET nome = EXCLUDED.nome;
        """, (id_whatsapp, nome))

        conn.commit()
        cur.close()
        conn.close()

        dispatcher.utter_message(text=f"Perfeito, {nome}! 😊")

        return [
            SlotSet("nome_cliente", nome),
            SlotSet("aguardando_nome", False),
            FollowupAction("action_iniciar_pedido")
        ]


class ActionIniciarPedido(Action):

    def name(self):
        return "action_iniciar_pedido"

    def run(self, dispatcher, tracker, domain):

        dispatcher.utter_message(
            text="🥚 Vamos começar seu pedido!"
        )

        return [
            FollowupAction("fechar_pedido_form")
        ]

class ActionCalcularValoresPedido(Action):

    def name(self) -> Text:
        return "action_calcular_valores_pedido"

    def run(
        self,
        dispatcher: CollectingDispatcher,
        tracker: Tracker,
        domain: Dict[Text, Any]
    ) -> List[Dict[Text, Any]]:

        nome_produto = tracker.get_slot("produto")
        qtd_texto = tracker.get_slot("quantidade")

        try:
            quantidade = int(
                ''.join(filter(str.isdigit, str(qtd_texto)))
            )
        except Exception:
            quantidade = 1

        try:
          
            produto = buscar_produto(nome_produto)

            print(f"DEBUG: Produto={nome_produto}, Qtd={qtd_texto}")

            if not produto:
                dispatcher.utter_message(
                    text=f"❌ Produto '{nome_produto}' não encontrado."
                )
                return []

            id_produto, preco_unitario = produto

    
            config = buscar_config()

            qtd_frete_gratis = int(
                config.get("qtd_frete_gratis", 5)
            )

            valor_frete_padrao = float(
                config.get("valor_frete_padrao", 10)
            )

      
            subtotal = float(preco_unitario) * quantidade

            if quantidade >= qtd_frete_gratis:
                custo_frete = 0.0
            else:
                custo_frete = valor_frete_padrao

            total = subtotal + custo_frete

            valor_entrega_str = f"{custo_frete:.2f}".replace(".", ",")
            valor_total_str = f"{total:.2f}".replace(".", ",")

            print(
                f"Produto={nome_produto} | "
                f"Qtd={quantidade} | "
                f"Preço={preco_unitario} | "
                f"Frete={custo_frete} | "
                f"Total={total}"
            )

            return [
                SlotSet("valor_entrega", valor_entrega_str),
                SlotSet("valor_total", valor_total_str)
            ]

        except Exception as e:
            print(f"Erro ao calcular valores do pedido: {e}")

            dispatcher.utter_message(
                text="❌ Erro ao calcular valores do pedido."
            )

            return []

class ActionLimparSlotParaAlterar(Action):

    def name(self) -> Text:
        return "action_limpar_slot_para_alterar"

    def run(
        self,
        dispatcher: CollectingDispatcher,
        tracker: Tracker,
        domain: Dict[Text, Any]
    ) -> List[Dict[Text, Any]]:

        intent = tracker.latest_message.get("intent", {}).get("name")

        mapa_campos = {
            "alterar_produto": "produto",
            "alterar_quantidade": "quantidade",
            "alterar_rua": "rua",
            "alterar_numero": "numero",
            "alterar_data": "data",
            "alterar_pagamento": "pagamento"
        }

        campo = mapa_campos.get(intent)

        if not campo:
            dispatcher.utter_message(
                text="Não consegui identificar o que você deseja alterar."
            )
            return []

        dispatcher.utter_message(
            text=f"✏️ Certo! Vamos alterar: {campo}"
        )

        return [SlotSet(campo, None)]

class ActionSalvarPedidoBanco(Action):

    def name(self) -> Text:
        return "action_salvar_pedido_banco"

    def run(
        self,
        dispatcher: CollectingDispatcher,
        tracker: Tracker,
        domain: Dict[Text, Any]
    ) -> List[Dict[Text, Any]]:

        id_whatsapp = str(tracker.sender_id)

        nome_cliente = tracker.get_slot("nome_cliente")

        nome_produto = tracker.get_slot("produto")
        qtd_texto = tracker.get_slot("quantidade")

        data_entrega_texto = tracker.get_slot("data")
        metodo_pagamento = tracker.get_slot("pagamento")

        rua = tracker.get_slot("rua")
        numero = tracker.get_slot("numero")
        bairro = tracker.get_slot("bairro")
        cep = tracker.get_slot("cep")
        complemento = tracker.get_slot("complemento")

        if not rua or not numero or not bairro:
            dispatcher.utter_message(
                text="⚠️ Endereço incompleto."
            )
            return []

        try:
            quantidade = int(
                ''.join(filter(str.isdigit, str(qtd_texto)))
            )
        except Exception:
            quantidade = 1

        conn = None
        cur = None

        try:
            conn = obter_conexao()
            cur = conn.cursor()


            cur.execute("""
                SELECT id, preco
                FROM produtos
                WHERE LOWER(nome) = LOWER(%s)
                AND ativo = true
                LIMIT 1
            """, (nome_produto,))

            produto = cur.fetchone()

            if not produto:
                dispatcher.utter_message(
                    text="❌ Produto não encontrado."
                )
                return []

            id_produto, preco_unitario = produto



            cur.execute("""
                SELECT chave, valor
                FROM configuracoes
            """)

            configs = {
                chave: valor
                for chave, valor in cur.fetchall()
            }

            qtd_frete_gratis = int(
                configs.get("qtd_frete_gratis", 5)
            )

            valor_frete_padrao = float(
                configs.get("valor_frete_padrao", 10)
            )



            subtotal = float(preco_unitario) * quantidade

            if quantidade >= qtd_frete_gratis:
                custo_frete = 0.0
            else:
                custo_frete = valor_frete_padrao

            total = subtotal + custo_frete



            data_entrega = datetime.date.today()

            if data_entrega_texto and "/" in str(data_entrega_texto):
                try:
                    dia, mes = data_entrega_texto.split("/")
                    data_entrega = datetime.date(
                        datetime.date.today().year,
                        int(mes),
                        int(dia)
                    )
                except Exception:
                    pass


            id_pedido = datetime.datetime.now().strftime(
                "%Y%m%d%H%M%S"
            )


            cur.execute("""
                INSERT INTO clientes (
                    id_whatsapp,
                    nome
                )
                VALUES (%s, %s)
                ON CONFLICT (id_whatsapp)
                DO UPDATE SET
                    nome = EXCLUDED.nome;
            """, (
                id_whatsapp,
                nome_cliente
            ))


            cur.execute("""
                INSERT INTO pedidos (
                    id,
                    id_cliente,
                    data_entrega,
                    status_entrega,
                    custo_frete,
                    subtotal,
                    total
                )
                VALUES (
                    %s,%s,%s,
                    'Pendente',
                    %s,%s,%s
                );
            """, (
                id_pedido,
                id_whatsapp,
                data_entrega,
                custo_frete,
                subtotal,
                total
            ))


            cur.execute("""
                INSERT INTO itens_pedido (
                    id_pedido,
                    id_produto,
                    quantidade,
                    preco_unitario,
                    valor_item
                )
                VALUES (
                    %s,%s,%s,%s,%s
                );
            """, (
                id_pedido,
                id_produto,
                quantidade,
                preco_unitario,
                subtotal
            ))

            cur.execute("""
                INSERT INTO endereco_entrega (
                    id_pedido,
                    id_cliente,
                    rua,
                    numero,
                    bairro,
                    cep,
                    complemento
                )
                VALUES (
                    %s,%s,%s,%s,%s,%s,%s
                );
            """, (
                id_pedido,
                id_whatsapp,
                rua,
                numero,
                bairro,
                cep,
                complemento
            ))



            cur.execute("""
                INSERT INTO pagamentos (
                    id_pedido,
                    metodo_pagamento,
                    valor,
                    status_pagamento
                )
                VALUES (
                    %s,%s,%s,'Pendente'
                );
            """, (
                id_pedido,
                metodo_pagamento,
                total
            ))

            conn.commit()

            dispatcher.utter_message(
                text=f"✅ Pedido realizado com sucesso, {nome_cliente}! Agora é só aguardar a entrega. Obrigado pela preferência! 🐔🥚"

            )

        except Exception as e:
            print(f"Erro ao salvar pedido: {e}")

            if conn:
                conn.rollback()

            dispatcher.utter_message(
                text="❌ Ocorreu um erro ao salvar o pedido."
            )

            return []

        finally:
            if cur:
                cur.close()

            if conn:
                conn.close()

        return [AllSlotsReset()]

class ActionBuscarPedidosBanco(Action):
    def name(self) -> Text:
        return "action_buscar_pedidos_banco"

    def run(self, dispatcher: CollectingDispatcher, tracker: Tracker, domain: Dict[Text, Any]) -> List[Dict[Text, Any]]:
        id_whatsapp = str(tracker.sender_id)

        conn = None
        cur = None
        try:
            conn = obter_conexao()
            cur = conn.cursor()

            cur.execute("""
                SELECT p.id, p.status_entrega, p.total, p.data_entrega, pr.nome, ip.quantidade
                FROM pedidos p
                JOIN itens_pedido ip ON p.id = ip.id_pedido
                JOIN produtos pr ON ip.id_produto = pr.id
                WHERE p.id_cliente = %s
                AND p.status_entrega IN ('Pendente', 'Em Processo de Entrega')
                ORDER BY p.data_criacao DESC;
            """, (id_whatsapp,))
            
            pedidos = cur.fetchall()

            if pedidos:
                msg = "📦 *Seus Pedidos Encontrados:*\n\n"
                for item in pedidos:
                    pid, status, total, data, produto, qtd = item
                    data_str = data.strftime("%d/%m/%Y") if isinstance(data, datetime.date) else str(data)
                    valor_str = f"{total:.2f}".replace('.', ',')
                    emoji_status = "⏳" if status == "Pendente" else "✅" if status == "Entregue" else "❌"
                    
                    msg += (f"• *Pedido:* #{pid}\n"
                            f"  • *Produto:* {produto} ({qtd}x)\n"
                            f"  • *Total:* R$ {valor_str}\n"
                            f"  • *Entrega:* {data_str}\n"
                            f"  • *Status:* {emoji_status} {status}\n"
                            f"  ────────────────────\n")
                
                dispatcher.utter_message(text=msg)
            else:
                dispatcher.utter_message(text="🔍 Você ainda não possui nenhum pedido pendente ou em processo de entrega.")
                
        except Exception as e:
            print(f"Erro ao listar pedidos: {e}")
            dispatcher.utter_message(text="⚠️ Desculpe, tive um problema ao conectar com o banco de dados para listar seus pedidos.")
        finally:
            if cur: cur.close()
            if conn: conn.close()

        return []


class ActionCancelarPedidoBanco(Action):
    def name(self) -> Text:
        return "action_cancelar_pedido_banco"

    def run(self, dispatcher: CollectingDispatcher, tracker: Tracker, domain: Dict[Text, Any]) -> List[Dict[Text, Any]]:
        numero_pedido_raw = tracker.get_slot("numero")
        if not numero_pedido_raw:
            numero_pedido_raw = tracker.latest_message.get('text')

        try:
            id_pedido = str(''.join(filter(str.isdigit, str(numero_pedido_raw))))
        except Exception:
            dispatcher.utter_message(text="⚠️ O código informado não parece válido. Certifique-se de digitar apenas os números.")
            return [SlotSet("numero", None)]

        conn = None
        cur = None
        try:
            conn = obter_conexao()
            cur = conn.cursor()

            cur.execute("SELECT status_entrega FROM pedidos WHERE id = %s;", (id_pedido,))
            resultado = cur.fetchone()

            if resultado is None:
                dispatcher.utter_message(text=f"🔍 Não encontrei nenhum pedido com o código *{id_pedido}* em nosso sistema.")
                return [SlotSet("numero", None)]
            
            if resultado[0] == 'Cancelado':
                dispatcher.utter_message(text="💡 Este pedido já consta como cancelado no sistema.")
                return [SlotSet("numero", None)]

            cur.execute("UPDATE pedidos SET status_entrega = 'Cancelado' WHERE id = %s;", (id_pedido,))
            conn.commit()
            
            try:
                dispatcher.utter_message(response="utter_cancelar_pedido_confirmar")
            except Exception:
                dispatcher.utter_message(text="✅ Perfeito! O seu pedido foi cancelado com sucesso.")

        except Exception as e:
            print(f"Erro ao cancelar pedido no banco: {e}")
            if conn: conn.rollback()
            dispatcher.utter_message(text="Desculpe, tive uma falha ao tentar atualizar o banco de dados.")
        finally:
            if cur: cur.close()
            if conn: conn.close()

        return [SlotSet("numero", None)]