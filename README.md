# WatchTower
### 一个MC服务器性能监视器

---

使用WebSocket协议向指定的服务器汇报MC服务器性能指标，包括玩家数，最大玩家数，TPS，MSPT，Java的CPU使用情况，堆内存当前大小，堆内存最大值，已使用的堆大小。

## 配置文件
配置文件在`config/watchtower.json`

示例:
```json
{
  "address": "example.com",
  "port": 8080,
  "password": "your_password",
  "ssl": true
}
```

## 协议说明

主要分为两种，发送和回应。  
发送格式:
```json
{
  "type": "type",
  "id": "id",
  "data": "data"
}
```
其中`data`可以是任何类型，视目的而定。  
回应格式：
```json
{
  "id": "id",
  "data": "data"
}
```
`data`说明同上。  
其中，从客户端发送还需要额外带上登录时分发的token。

存在种特殊格式，`type`为`auth`时不带`token`和`id`, 回应的`auth_success`的`data`字段为`token`，并使用`type`而非`id`。

数据包的具体载荷见`org.minelogy.watchtower.Network`

## 依赖

采集系统资源情况依赖`com.sun.management.OperatingSystemMXBean`，仅在Oracle JDK，OpenJDK及其衍生版本上可用
