# dubbox-async

dubbox-async: making [dubbox](https://github.com/dangdangdotcom/dubbox) (for 2.8.4) to support full async call chain by using [CompletableFuture](http://docs.oracle.com/javase/8/docs/api/java/util/concurrent/CompletableFuture.html).

## Project Info
Dubbox supports Future interface, when requests for multiple services, the cost time depends on the longest response time of the services. It reduces the total response time, but still blocking the main request thread.

This project is to make up for the deficiency of Future. Now by calling RpcContext.getContext().getFuture(), consumer side gets a CompletableFuture, supports dependent functions and actions that trigger upon its completion.

This project contains several modules of dubbox :
* dubbo-remoting-api
* dubbo-rpc-api
* dubbo-rpc-default
   
## Build-time Requirement
Clone or download this project, and replace the modules. Then use Apache Maven to rebuild you project.
* Oracle [JDK 8](http://www.oracle.com/technetwork/java/).

## Examples
```xml
<dubbo:reference id="service1" interface="com.github.service.Service1">
      <dubbo:method name="method1" async="true" />
</dubbo:reference>
<dubbo:reference id="service2" interface="com.github.service.Service2">
      <dubbo:method name="method2" async="true" />
</dubbo:reference>
<dubbo:reference id="service3" interface="com.github.service.Service3" />
```

Here is some examples:

simply get
```java
//return null
service1.method1();
CompletableFuture<Object> future = RpcContext.getContext().getFuture();
//wait until get a result
Object object = future.get();
//or provide a max time to wait
Object object = future.get(5, TimeUnit.SECONDS);
```

call method1 and then method2
```java
//the result of service1.method1 as the param of service2.method2
service1.method1();
CompletableFuture<Object> future = RpcContext.getContext().getFuture();
future.thenAccept(service2::method2)
```

process when method1 and method2 completes normally
```java
service1.method1();
CompletableFuture<Object> future1 = RpcContext.getContext().getFuture();
service2.method2();
CompletableFuture<Object> future2 = RpcContext.getContext().getFuture();
future1.thenCombine(future2, (object1, object2) -> {
    //process...
});
```

call method3 until method1 and method2 completes
```java
service1.method1();
CompletableFuture<Object> future1 = RpcContext.getContext().getFuture();
service2.method2();
CompletableFuture<Object> future2 = RpcContext.getContext().getFuture();
CompletableFuture.allOf(future1, future2).thenApply(service3::method3);
```
