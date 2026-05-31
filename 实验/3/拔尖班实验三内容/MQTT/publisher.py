import paho.mqtt.client as mqtt
import time

BROKER_IP = "192.168.1.101"		##第1步中获取到的IP地址

client = mqtt.Client()

client.connect(BROKER_IP, 1883, 60)

time.sleep(1)

client.publish("chat/message", "QoS2 Message", qos=2)


print("消息发送成功")

client.disconnect()
