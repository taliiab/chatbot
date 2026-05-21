from typing import Any, Text, Dict, List
import datetime
import psycopg2
from rasa_sdk import Action, Tracker
from rasa_sdk.events import SlotSet, AllSlotsReset
from rasa_sdk.executor import CollectingDispatcher

def obter_conexao():
    return psycopg2.connect(
        dbname="rasa", user="postgres", password="admin",
        host="localhost", port="5432"
    )

class ActionCalcularValoresPedido(Action):
    def name(self) -> Text:
        return "action_calcular_valores_pedido"

    def run(self, dispatcher: CollectingDispatcher, tracker: Tracker, domain: Dict[Text, Any]) -> List[Dict[Text, Any]]:
        nome_produto = tracker.get_slot("produto")
        qtd_texto = tracker.get_slot("quantidade")

        # Tratamento seguro da Quantidade
        try:
            quantidade = int(''.join(filter(str.isdigit, str(qtd_texto))))
        except Exception:
            quantidade = 1

        # Regra de negócio dos produtos alinhada ao banco (Id 1 = Extra, Id 2 = Jumbo)
        id_produto = 1 if nome_produto and nome_produto.lower() == "extra" else 2
        preco_unitario = 18.00 if id_produto == 1 else 19.00
        
        # Regra do frete grátis (5 ou mais unidades)
        custo_frete = 0.00 if quantidade >= 5 else 10.00
        subtotal = preco_unitario * quantidade
        total = subtotal + custo_frete

        # Formata os valores para exibição no padrão brasileiro
        valor_entrega_str = f"{custo_frete:.2f}".replace('.', ',')
        valor_total_str = f"{total:.2f}".replace('.', ',')

        return [
            SlotSet("valor_entrega", valor_entrega_str),
            SlotSet("valor_total", valor_total_str)
        ]


class ActionLimparSlotParaAlterar(Action):
    """
    Esta action limpa o slot que o usuário deseja alterar, forçando 
    o formulário a perguntar por ele novamente no próximo passo.
    """
    def name(self) -> Text:
        return "action_limpar_slot_para_alterar"

    def run(self, dispatcher: CollectingDispatcher, tracker: Tracker, domain: Dict[Text, Any]) -> List[Dict[Text, Any]]:
        campo = tracker.get_slot("campo_alterar")
        
        if campo:
            dispatcher.utter_message(text=f"Certo, vamos corrigir o campo: *{campo}*.")
            return [SlotSet(campo, None), SlotSet("campo_alterar", None)]
        
        return []


class ActionSalvarPedidoBanco(Action):
    def name(self) -> Text:
        return "action_salvar_pedido_banco"

    def run(self, dispatcher: CollectingDispatcher, tracker: Tracker, domain: Dict[Text, Any]) -> List[Dict[Text, Any]]:
        id_whatsapp = str(tracker.sender_id)
        nome_produto = tracker.get_slot("produto")
        qtd_texto = tracker.get_slot("quantidade")
        data_entrega_texto = tracker.get_slot("data")
        metodo_pagamento = tracker.get_slot("pagamento")
        rua = tracker.get_slot("rua")
        numero = tracker.get_slot("numero")
        bairro = tracker.get_slot("bairro")
        cep = tracker.get_slot("cep")
        complemento = tracker.get_slot("complemento")

        # Blindagem contra desvios de fluxo: evita salvar dados obrigatórios nulos no banco
        if not rua or not numero or not bairro:
            dispatcher.utter_message(
                text="⚠️ Ops! Notei que os dados de endereço para a entrega estão incompletos. Vamos tentar preencher novamente?"
            )
            return []

        try:
            quantidade = int(''.join(filter(str.isdigit, str(qtd_texto))))
        except Exception:
            quantidade = 1

        id_produto = 1 if nome_produto and nome_produto.lower() == "extra" else 2
        preco_unitario = 18.00 if id_produto == 1 else 19.00
        custo_frete = 0.00 if quantidade >= 5 else 10.00
        subtotal = preco_unitario * quantidade
        total = subtotal + custo_frete

        data_entrega = datetime.date.today()
        if data_entrega_texto and '/' in str(data_entrega_texto):
            try:
                partes = data_entrega_texto.split('/')
                data_entrega = datetime.date(2026, int(partes[1]), int(partes[0]))
            except Exception:
                pass 

        id_pedido = datetime.datetime.now().strftime("%d%H%M%S")

        conn = None
        cur = None
        try:
            conn = obter_conexao()
            cur = conn.cursor()

            # 1. Garante a existência do cliente
            cur.execute("""
                INSERT INTO clientes (id_whatsapp, nome) 
                VALUES (%s, %s) 
                ON CONFLICT (id_whatsapp) DO NOTHING;
            """, (id_whatsapp, "Cliente WhatsApp"))
            
            # 2. Cria o registro do pedido
            cur.execute("""
                INSERT INTO pedidos (id, id_cliente, data_entrega, status_entrega, custo_frete, subtotal, total) 
                VALUES (%s, %s, %s, 'Pendente', %s, %s, %s);
            """, (id_pedido, id_whatsapp, data_entrega, custo_frete, subtotal, total))

            # 3. Associa o item ao pedido
            cur.execute("""
                INSERT INTO itens_pedido (id_pedido, id_produto, quantidade, preco_unitario, valor_item) 
                VALUES (%s, %s, %s, %s, %s);
            """, (id_pedido, id_produto, quantidade, preco_unitario, subtotal))

            # 4. Mapeamento explícito de colunas incluindo o id_cliente
            cur.execute("""
                INSERT INTO endereco_entrega (id_pedido, id_cliente, rua, numero, bairro, cep, complemento) 
                VALUES (%s, %s, %s, %s, %s, %s, %s);
            """, (id_pedido, id_whatsapp, rua, numero, bairro, cep, complemento))

            # 5. Registra a transação de pagamento
            cur.execute("""
                INSERT INTO pagamentos (id_pedido, metodo_pagamento, valor, status_pagamento) 
                VALUES (%s, %s, %s, 'Pendente');
            """, (id_pedido, metodo_pagamento if metodo_pagamento else "Não Informado", total))

            conn.commit()

            # 💬 Mensagem enviada antes de limpar a memória para garantir o retorno no chat
            dispatcher.utter_message(text="✅ Seu pedido foi realizado com SUCESSO!")
            dispatcher.utter_message(text="Agora é só aguardar, nossa equipe já está preparando! 🐔🥚\nAgradecemos pela preferência! 💛")
            
        except Exception as e:
            print(f"Erro crítico ao salvar no banco: {e}")
            if conn: conn.rollback()
            dispatcher.utter_message(text="Desculpe, tive um problema interno ao processar seu pedido. Por favor, tente novamente.")
            return []
        finally:
            if cur: cur.close()
            if conn: conn.close()
        
        # Limpa todos os slots de uma vez usando o evento nativo do Rasa
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
                dispatcher.utter_message(text="🔍 Você ainda não possui nenhum pedido cadastrado no nosso sistema.")
                
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