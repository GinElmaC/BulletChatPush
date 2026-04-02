这是一款基于Netty实现的弹幕消息推送中台项目，具体的架构图：
<img width="2396" height="959" alt="b43ccbee6a27b3e7d2559324f4d22ea0" src="https://github.com/user-attachments/assets/e8cea824-106b-4497-b4fd-c34117c55eb9" />

其中对于上行流量计划使用gateway进行管理，将流量分给中台的不同节点，节点将消息发送给直播的业务层做业务处理，例如弹幕风控、账号经验增加等；业务层将消息传回中台后，根据中台维护的目标用户uid或者直播间zid与机器id的映射，转发到对应的机器上完成发送。

自测环节：
笔记本8G可用内存，启动nacos、server以及单push。
阿里云服务器2G4核，搭载kafka、redis、mysql
使用就jmeter进行压测，然后通过redis中的key得出目前配置最大连接数为6000+
<img width="1029" height="764" alt="image" src="https://github.com/user-attachments/assets/517565fa-8c23-4c25-93e5-054c97aa6148" />

