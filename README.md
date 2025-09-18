# Lamada
A JVM framework to execute code remotely via functional interfaces (lambdas)

## Quickstart
For now the code isn't published anywhere, but documentation further assumes you have Lamada in your classpath

### Create a DistributedExecutor
```java
DistributedExecutor<String> executor 
    = new DistributedExecutor<>(myTarget);
```
Type param specifies the type of target (JVMs) which are managed. 
It can be a String for redis, a Socket for network-pure implementations and so on.

This tutorial will further use Redis (lamada-redis-impl) as an example
```java
RedisImplementation impl = new RedisImplementation(executor, jedisPool);
executor.setTargetManager(impl);
executor.setSender(impl);
```
Redis implementation combines sender and target manager in a single class<br>
Now we register DistributedObject's. Those are entities of your workload, which can be identified by all JVMs by some identifier.
Generally all you need is your entity class, key class and a way to (de)serialize the objects from a key
Lamada provides FunctionalDistributedObject where you won't need to override any methods.

```java
FunctionalDistributedObject<UUID, Player, String> players 
    = new FunctionalDisitributedObject(executor, Player.class, UUID.class, true);
```
First 3 params are self-explanatory. 4th param is labelled as 'unique'. 
Unique means an object only exists at 1 JVM during runtime. **Only <ins>interfaces</ins> can be unique objects**.
That is to ensure predictability. If you were to mention a variable of Player and have some logic on that Player,
Lamada will generate a stub class which will send your call back to the sender and have the result back.

Final step is to sync the executor. **All JVMs must register objects (and everything else) in the same order**
```java
executor.sync();
```
Some implementations support registering objects after initial sync (by implementing the resync method),
but it's generally advised not to

Now you can use the object to execute code remotely
```java
Player whoSentHello = ...;
players.run("other-target", otherPlayerUuid, player -> {
   player.sendMessage(whoSentHello.getName() + " says hello to you!"); 
}).join(); // run methods return a CompletableFuture
```
You can also evaluate something from a remote JVM
```java
Player whoSentHello = ...;
players.runMethod("other-target", otherPlayerUuid, player -> {
    return "We have evaluated a name: " + whoSentHello.getName();    
}).join(); // we will get that string back
```

If you want to wrap run and runMethod, you may only use functional interfaces which begin with Execution (ExecutionRunnable, ExecutionConsumer etc)
That also applies if you just want to mention another lambda instance in the remote lambda. 
Development of a Java Agent to overcome this limitation is already in progress

