import paho.mqtt.client as mqtt

BROKER_IP = "192.168.1.101" 	##第1步中获取到的IP地址


def on_connect(client, userdata, flags, rc):
    print("成功连接 Broker")
    client.subscribe("chat/message", qos=2)

def on_message(client, userdata, msg):
    print("收到消息：", msg.payload.decode())

client = mqtt.Client()

client.on_connect = on_connect
client.on_message = on_message

client.connect(BROKER_IP, 1883, 60)

client.loop_forever()
