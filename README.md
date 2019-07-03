# 高并发之限流，到底限的什么鬼

你可能知道高并发系统需要限流这个东西，但具体是限制的什么，该如何去做，还是临摹两可。我们接下来系统性的给它归个小类，希望对你有所帮助。

google guava中提供了一个限流实现: RateLimiter，这个类设计的非常精巧，可以适用于我们日常业务中大多数`流控`的场景，但鉴于使用场景的多样性，使用时也需要相当小心。

前面已经使用两篇简单的文章进行了预热。  
[信号量限流，高并发场景不得不说的秘密](https://mp.weixin.qq.com/s/gWecABdonaYah4nHr9hDfg)  
[没有预热，不叫高并发，叫并发高](https://mp.weixin.qq.com/s/IVFf2B7UPY2XaJUgAM3sjg)

这次不同。本篇文章将详细的，深入的介绍限流的各种场景和属性，然后分析guava这个限流器的核心源码，并对其特性进行总结。属于稍高级的进阶篇。

# 限流场景

弄清楚你要限制的资源，是这个过程中最重要的一环。我大体将它分为三类。
![](media/15621186881618/15621236584571.jpg)

### 代理层

比如SLB、nginx或者业务层gateway等，都支持限流，通常是基于`连接数`（或者并发数）、`请求数`进行限流。限流的维度通常是基于比如IP地址、资源位置、用户标志等。更进一步，还可以根据自身负载情况动态调整限流的策略（基准）。
 
### 服务调用者

服务调用方，也可以叫做本地限流，客户端可以限制某个远端服务的调用速度，超过阈值，可以直接进行阻塞或者拒绝，是限流的`协作方`。

###  服务接收方

基本同上，流量超过系统承载能力时，会直接拒绝服务。通常基于应用本身的可靠性考虑，属于限流的`主体方`。我们常说的限流，一般发生在此处。本文主要结合RateLimiter讨论基于限流主体方的使用方式，其他的都类似。

# 限流策略

限流策略有时候很简单，有时候又很复杂，但常见的就三种。其他的都是在这之上进行的改进和扩展。
![](media/15621186881618/15621251498076.jpg)
## 根据并发级别限流
 
这是一种简单的、易于实施的限流方式，可以使用我们前面提到的java信号量实现。它的使用场景也有着比较鲜明的特点：
 
 1）每次请求，所需要的资源开支都比较均衡，比如，每个请求对CPU的消耗、IO消耗等，都差不多，请求的RT时间都基本接近。  
2） 请求密度或稀疏或高频，这个我们不去关注。  
3）资源本身不需要繁琐的初始化工作（`预热`），或者初始化工作的开支可以忽略。(会增加复杂度)  
4）对待流量溢出的策略比较简单，通常是`直接拒绝`而不是等待，因为等待往往意味着故障。

这种策略通常在适用在流量的顶层组件上，比如代理层、中间件等对并发连接数的限制。而尝试获取凭证的超时时间，就叫做`溢出等待`。很上档次很装b的词，对不对？

## 漏桶算法

请求流量以不确定速率申请资源，程序处理以恒定的速率进行，就是漏桶算法的基本原理。有点像制作冰激凌的过程。-.- 有关漏桶模型，大家可以去研究一下相关资料。

大体有以下几个概念。

### 桶 buffer

请求首先尝试进入队列，如果队列溢满，则拒绝此请求。进入队列以后，请求则等待执行。

由此可见，请求究竟何时被执行，还存在一些变数，这直接取决于队列中pending的请求数。有时候，挑剔的设计者会考虑增加有关限制请求等待的时间阈值，这个时间就是请求入队、出队的最大时差。buffer的大小设计，通常与速率有直接关系。

### 漏：请求出队

这个出队，有些讲究，不同的设计理念实现就有所不同。有`抢占式`、有`调度式`。其中“抢占式”就是处理线程（或者进程，比如nginx worker进程）在上一个请求处理完毕之后即从buffer队列中poll新的请求，无论当前线程（或者进程）的处理速率是否超过设定的速率，这种策略下buffer大小就限定了速率的上限。

调度式，就比较易于理解，需要额外的调度线程（进程），并严格按照设定的速率，从buffer中获取请求，并轮训的方式将请求交给其他worker线程，如果已有的worker线程繁忙，则直接创建新线程，目的就是确保速率是有保障的，这种方式下，buffer大小主要取决于等待时间。

### 溢出

就是因为漏桶的速率限制比较稳定，所以其面临流量突发（bursty）几乎没有应对能力，简单来说，超出buffer，就直接拒绝。

多么可怜的请求们。

### 流量突发

尽管buffer的设计在一定层面上兼顾流量突发，但是还是太脆弱了，比如某个瞬间，请求密度很高（最尴尬的就是，只大了一点），将buffer溢满，或许buffer再“大一点点”就能够在合理时间内被处理；对于请求方，就会有些迷惑，“我只不过是稍微超了一点，你就给了我一连串无法工作的信息，so nave!!!”。

 这种策略，也很常用，但是通常适用在限流的协作方，也是就客户端层面。请求发出之前，做流控，如果有溢出，就要用其他可靠的策略来保障结果，比如重试等；反正 “对面的服务压垮了，别怪我，我很自律”。
 
## 令牌桶

设计模型，我就不再介绍，大家可以去wiki深入了解一下。

令牌桶的基本思想，跟老一辈的集体公社时代一样，每个月的供销是限额的，有资源才分配给个人，不足部分下个月再说，你可以排队赊账。

令牌的个数，就是可以允许获取资源的请求个数（我们假设每个请求只需要一个令牌）。事实上，我们并不会真的去创建令牌实体，因为这是没有必要的，我们使用带有时间特征的计数器来表示令牌的可用个数即可。跟漏桶算法相比，令牌桶的“桶”不是用来buffer请求的、而是用来计量可用资源数量（令牌）的。虽然我们并不会创建令牌实体，但是仍然可以假想，这个桶内每隔X时间就会新增一定数量的令牌，如果没有请求申请令牌，那么这个令牌桶是会溢出的...你会发现，这个设计跟漏桶算法从IO方向上是相反的。

那么漏桶算法的缺点，也正好成为了令牌桶的专长：流量突发；令牌桶成了buffer，如果请求密度低，或者处于冷却状态，那么令牌桶就会溢满，此后如果流量突发，则过去积累的结余资源则可以直接被“借用”。

令牌桶算法，使用场景很多，适应程度很高，现实中流量突发是常见的，而且从设计角度考虑，令牌桶更易于实现。回到正题，RateLimiter，就是一个基于令牌桶思想的实现。

我们的口子越缩越小，终于到正题了。

#  RateLimiter使用

guava的api已经把它的设计思想阐述的比较清楚了,但是这个注释阅读起来还是稍微有点“哲学派”,我们先看两个栗子，然后从源码层面看下它的设计原理。

```
//RateLimiter limiter = RateLimiter.create(10,2, TimeUnit.SECONDS);//QPS 100  
RateLimiter limiter = RateLimiter.create(10);  
long start = System.currentTimeMillis();  
for (int i= 0; i < 30; i++) {  
    double time = limiter.acquire();  
    long after = System.currentTimeMillis() - start;  
    if (time > 0D) {  
        System.out.println(i + ",limited,等待:" + time + "，已开始" + after + "毫秒");  
    } else {  
        System.out.println(i + ",enough" + "，已开始" + after + "毫秒");  
    }  
    //模拟冷却时间，下一次loop可以认为是bursty开始  
    if (i == 9) {  
        Thread.sleep(2000);  
    }  
}  
System.out.println("total time：" + (System.currentTimeMillis() - start));   
```

此例为简单的流控，只有一种资源，QPS为`10`；在实际业务场景中，可能不同的资源速率是不同的，我们可以创建N多个limeter各自服务于资源。

acquire()方法就是获取一个令牌（源码中使用permit，许可证），如果permit足够，则直接返回而无需等待，如果不足，则等待1/QPS秒。

此外，你会发现， **limiter并没有类似于锁机制中的release()方法** ，这意味着“只要申请，总会成功”、且退出时也无需归还。

**RateLimiter内部有两种实现：（下文中，“资源”、“令牌”、“permits”为同一含义）**

## SmoothBursty

可以支持“突发流量”的限流器，即当限流器不被使用时间，可以额外存储一些permits以备突发流量，当突发流量发生时可以更快更充分的使用资源，流量平稳后（或者冷却期，积累的permits被使用完之后）速率处于限制状态。

其重点就是，冷却期间，permits会积累，且在突发流量时，可以消耗此前积累的permits而且无需任何等待。就像一个人，奔跑之后休息一段时间，再次起步可以有更高的速度。

由此可见，如果你的资源，冷却（不被使用）一段时间之后，再次被使用时可以提供比正常更高的效率，这个时候，你可以使用SmoothBursty。

 
创建方式
```java
RateLimiter.create(double permitsPerSecond)
```

结果类似
```
0,enough，已开始1毫秒  
1,limited,等待:0.098623，已开始105毫秒  
2,limited,等待:0.093421，已开始202毫秒  
3,limited,等待:0.098287，已开始304毫秒  
4,limited,等待:0.096025，已开始401毫秒  
5,limited,等待:0.098969，已开始505毫秒  
6,limited,等待:0.094892，已开始605毫秒  
7,limited,等待:0.094945，已开始701毫秒  
8,limited,等待:0.099145，已开始801毫秒  
9,limited,等待:0.09886，已开始905毫秒  
10,enough，已开始2908毫秒  
11,enough，已开始2908毫秒  
12,enough，已开始2908毫秒  
13,enough，已开始2908毫秒  
14,enough，已开始2908毫秒  
15,enough，已开始2908毫秒  
16,enough，已开始2908毫秒  
17,enough，已开始2908毫秒  
18,enough，已开始2908毫秒  
19,enough，已开始2908毫秒  
20,enough，已开始2909毫秒  
21,limited,等待:0.099283，已开始3011毫秒  
22,limited,等待:0.096308，已开始3108毫秒  
23,limited,等待:0.099389，已开始3211毫秒  
24,limited,等待:0.096674，已开始3313毫秒  
25,limited,等待:0.094783，已开始3411毫秒  
26,limited,等待:0.097161，已开始3508毫秒  
27,limited,等待:0.099877，已开始3610毫秒  
28,limited,等待:0.097551，已开始3713毫秒  
29,limited,等待:0.094606，已开始3809毫秒  
total time：3809  
```

## SmoothWarmingUp

具有warming up（预热）特性，即突发流量发生时，不能立即达到最大速率，而是需要指定的“预热时间”内逐步上升最终达到阈值；它的设计哲学，与SmoothBursty相反，当突发流量发生时，以可控的慢速、逐步使用资源（直到最高速率），流量平稳后速率处于限制状态。

其重点是，资源一直被使用，那么它可以持续限制稳定的速率；否则，冷却时间越长（有效时长为warmup间隔）获取permits时等待的时间越长，需要注意，冷却时间会积累permits，但是获取这些permits仍然需要等待。

由此可见，如果你的资源，冷却（不被使用）一段时间之后，再次被使用时它需要一定的准备工作，此时它所能提供的效率比正常要低；比如链接池、数据库缓存等。

创建方式
```java
RateLimiter.create(double permitsPerSecond,long warnupPeriod,TimeUnit unit)
```
执行结果如下，可以看到有一个明显的增长过程。
```
0,enough，已开始1毫秒  
1,limited,等待:0.288847，已开始295毫秒  
2,limited,等待:0.263403，已开始562毫秒  
3,limited,等待:0.247548，已开始813毫秒  
4,limited,等待:0.226932，已开始1041毫秒  
5,limited,等待:0.208087，已开始1250毫秒  
6,limited,等待:0.189501，已开始1444毫秒  
7,limited,等待:0.165301，已开始1614毫秒  
8,limited,等待:0.145779，已开始1761毫秒  
9,limited,等待:0.128851，已开始1891毫秒  
10,enough，已开始3895毫秒  
11,limited,等待:0.289809，已开始4190毫秒  
12,limited,等待:0.264528，已开始4458毫秒  
13,limited,等待:0.247363，已开始4710毫秒  
14,limited,等待:0.225157，已开始4939毫秒  
15,limited,等待:0.206337，已开始5146毫秒  
16,limited,等待:0.189213，已开始5337毫秒  
17,limited,等待:0.167642，已开始5510毫秒  
18,limited,等待:0.145383，已开始5660毫秒  
19,limited,等待:0.125097，已开始5786毫秒  
20,limited,等待:0.109232，已开始5898毫秒  
21,limited,等待:0.096613，已开始5999毫秒  
22,limited,等待:0.096321，已开始6098毫秒  
23,limited,等待:0.097558，已开始6200毫秒  
24,limited,等待:0.095132，已开始6299毫秒  
25,limited,等待:0.095495，已开始6399毫秒  
26,limited,等待:0.096352，已开始6496毫秒  
27,limited,等待:0.098641，已开始6597毫秒  
28,limited,等待:0.097883，已开始6697毫秒  
29,limited,等待:0.09839，已开始6798毫秒  
total time：6798  
```

# acquire方法源码分析 

上面两个类都继承自SmoothRateLimiter，最终继承自RateLimiter；RateLimiter内部核心的方法：

 1）double acquire()：获取一个permit，如果permits充足则直接返回，否则等待1/QPS秒。此方法返回线程等待的时间（秒），如果返回0.0表示未限流、未等待。

2）double acquire(int n)：获取n个permits，如果permits充足则直接返回，否则限流并等待，等待时间为“不足的permits个数 / QPS”。（暂且这么解释）

下面就是这个方法的伪代码啦。
```
//伪代码  
public double acquire(int requiredPermits) {  
    long waitTime = 0L;  
    synchronized(mutex) {  
          boolean cold = nextTicketTime > now;  
          if (cold) {  
             storedPermits = 根据冷却时长计算累积的permits;  
             nextTicketTime = now;  
          }  
          //根据storedPermits、requiredPermits计算需要等待的时间  
          //bursty：如果storePermits足够，则waitTime = 0  
          //warmup：平滑预热，storePermits越多（即冷却时间越长），等待时间越长  
          if(storedPermits不足) {  
              waitTime += 欠缺的permits个数 / QPS;  
          }  
          if(bursty限流) {  
              waitTime += 0;//即无需额外等待  
          }  
          if(warmup限流) {  
              waitTime += requiredPermits / QPS;  
              if(storedPermits > 0.5 * maxPermits) {  
                waitTime += 阻尼时间;  
              }   
          }  
   
          nextTicketTime += waitTime  
    }  
    if (waitTime > 0L) {  
      Thread.sleep(waitTime);  
    }  
    return waitTime;  
}  
```

---

以下内容会比较枯燥～～～非常枯燥～～～

 1、Object mutex：同步锁，如上述伪代码所示，在计算存量permits、实际申请permits（包括计算）的过程中全部是同步的；我们需要知道，RateLimiter内部确实使用了锁同步机制。

2、maxPermits：最大可存储的许可数量（tickets数量），SmoothBursty和SmoothWarimingUp默认实现中，有所不同：  
1）SmoothBusty，其值为maxBurstSecond * QPS，就是允许“突发流量持续时间” * QPS，这种设计可以理解，不过RateLimiter将maxBustSecond硬编码为1.0，最终此值等于QPS。  
2）SmoothWarmingUp：默认算法值为warmupPeriod * QPS，简单说就是“预热时长” * QPS。

此参数主要限制，无论冷却多长时间，其storedPermits不能超过此值；此值在设定QPS之后，则不会再改变。

3、storedPermits：已存储的permits数量，此值取决于冷却时间，简单来说冷却的时间越久，此值越大，但不会超过maxPermits，起始值为0。  
 1）当一个请求，申请permit之前，将会计算上一次令牌申请（nexFreeTicketTime）的时间与now之间的时差，并根据令牌产生速率（1/QPS）计算此冷却期间可以存储的令牌数，也就是storedPermits。  
2）permits申请完毕之后，将当前时间（如果需要等待，额外加上等待时间）作为下一次令牌申请的起始时间，此时冷却时间结束。  
 3）申请完毕之后，storedPermits将会减去申请的permits个数，直到为0。

冷却时长和申请频次，都决定了storedPermits大小，其中冷却时间会导致storePermits增加，acquire操作将导致storePermits减少。

4、nextFreeTicketMicros（时间戳，微妙单位）：下一个可以自由获取的令牌的时间，此值可以为未来的某个时间，此时表示限流已经开始，部分请求已经在等待，此值主要用来标记“冷却状态”。（赊账）  
1）如果处于冷却期，那么此值通常是过去式，即此值小于now。  
2）如果此时有请求申请permits，则会通过此值与now的时差，计算storedPermits，同时将此值设置为now。  
3）如果此值是未来时刻，即大于now，则无需计算storedPermits，也无需重置此值。  
4）申请tickets后，从storedPermits减去需要的tickets个数，如果触发限速等待（比如预热期、permits不足），则会将2）操作之后额外增加等待时间作为nextFreeTicketsTime值。  
5）基于2），对于warmingUp限流，冷却期之后的首个请求是不需要等待的，只是将此值设置为now + 阻尼性质的等待时间waitTime（），这意味着在此后waitTime期间再有请求，则会触发等待，并继续延续nextFreeTicketMicros值。此值的延续，在warming up期间，阻尼waitTime计算比较复杂，由1/QPS + 额外值，这个额外值，随着预热时间增长而减小。  
6）基于2），对于bursty限流，如果storedPermits大于0，则总是不需要等待，只是简单将此值设为为now；否则，则按照正常的1/QPS间隔计算其应该被推延的时间点。  

 5、对于warming up限流，将maxPermits * 0.5作为一个阈值分割线，当storedPermits小于此分割线时，在限流时使用正常等待时间（申请permits个数 / QPS）；在此分割线之上时，则4）增加额外阻尼，即预热阻尼。
 
6、我们发现，RateLimiter内部并不会真的生成tickets实体，而是根据冷却时长、在申请资源时才计算存量tickets（对应为storedPermits）。无论何种限流，storedPermits都是优先使用。
    
# 小总结

是时候总结一下了。

RateLimiter是线程安全的，所以在并发环境中可以直接使用，而无需额外的lock或者同步。

考虑到RateLimiter内部的同步锁，我们通常在实际业务开发中，每个资源（比如URL）使用各自的RateLimiter而不是公用一个，占用的内存也不大。

这个限流器内部无额外的线程，也没有其他的数据结构用来存储tickets实体，所以它非常的轻量级，这也是优势所在。

**RateLimiter最大的问题，就是acquire方法总会成功，内部的tickets时间点会向后推移；** 如果并发很高，严重超过rate阈值时，后续被限流的请求，其等待时间将会基于时间线累加，导致等待时间不可控，这和信号量同病相怜。

为了避免上面的问题，我们通常先使用tryAcquired检测，如果可行再去acquire；如果令牌不足，适当拒绝。所以 **基于RateLimiter，并没有内置的拒绝策略，这一点需要我们额外开发。**

我们不能简单依赖于acquire方法，来实现限流等待，否则这可能带来严重问题。我们通常需要封装RateLimiter，并使用额外的属性记录其是否“处于限流状态”、“已经推延的tickets时间点”，如果“已经推延的时间点非常遥远”且超过可接受范围，则直接拒绝请求。简单来说，封装acquire方法，增加对请求可能等待时间的判断，如果超长，则直接拒绝。

RateLimiter存在一个很大的问题，就是几乎没法扩展：子类均为protected。反射除外哦。

# 一个实践

还是上一段代码吧，能更加清晰的看到我们所做的工作： FollowCotroller.java：流控器，如果限流开始，则只能有max个请求因此而等待，超过此值则直接拒绝

```
public class FollowController {  
  
    private final RateLimiter rateLimiter;  
  
    private int maxPermits;  
  
    private Object mutex = new Object();  
  
    //等待获取permits的请求个数，原则上可以通过maxPermits推算  
    private int maxWaitingRequests;  
  
    private AtomicInteger waitingRequests = new AtomicInteger(0);  
  
    public FollowController(int maxPermits,int maxWaitingRequests) {  
        this.maxPermits = maxPermits;  
        this.maxWaitingRequests = maxWaitingRequests;  
        rateLimiter = RateLimiter.create(maxPermits);  
    }  
  
    public FollowController(int permits,long warmUpPeriodAsSecond,int maxWaitingRequests) {  
        this.maxPermits = maxPermits;  
        this.maxWaitingRequests = maxWaitingRequests;  
        rateLimiter = RateLimiter.create(permits,warmUpPeriodAsSecond, TimeUnit.SECONDS);  
    }  
  
    public boolean acquire() {  
        return acquire(1);  
    }  
  
    public boolean acquire(int permits) {  
        boolean success = rateLimiter.tryAcquire(permits);  
        if (success) {  
            rateLimiter.acquire(permits);//可能有出入  
            return true;  
        }  
        if (waitingRequests.get() > maxWaitingRequests) {  
            return false;  
        }  
        waitingRequests.getAndAdd(permits);  
        rateLimiter.acquire(permits);  
  
        waitingRequests.getAndAdd(0 - permits);  
        return true;  
    }  
  
}  
```

以上代码，都可以在github找到。
```
https://github.com/sayhiai/example-ratelimit
```

# End

可以看到，guava提供了一个非常轻量而全面的限流器。它本身没有使用多线程去实现，但它是线程安全的。相比较信号量，它的使用简单的多。但鉴于限流场景的多样性，使用时同样要非常小心。
