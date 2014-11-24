/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ipc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.security.sasl.Sasl;
import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.IntWritable;
import static org.apache.hadoop.fs.CommonConfigurationKeys.*;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.ipc.metrics.RpcInstrumentation;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.SaslRpcServer;
import org.apache.hadoop.security.SaslRpcServer.SaslStatus;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.SaslRpcServer.AuthMethod;
import org.apache.hadoop.security.SaslRpcServer.SaslDigestCallbackHandler;
import org.apache.hadoop.security.SaslRpcServer.SaslGssCallbackHandler;
import org.apache.hadoop.security.UserGroupInformation.AuthenticationMethod;
import org.apache.hadoop.security.authorize.AuthorizationException;
import org.apache.hadoop.security.authorize.ProxyUsers;
import org.apache.hadoop.security.authorize.ServiceAuthorizationManager;
import org.apache.hadoop.security.token.SecretManager;
import org.apache.hadoop.security.token.SecretManager.InvalidToken;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.StringUtils;

/** An abstract IPC service.  IPC calls take a single {@link Writable} as a
 * parameter, and return a {@link Writable} as their value.  A service runs on
 * a port and is defined by a parameter class and a value class.
 * 
 * @see Client
 */
public abstract class Server {
  private final boolean authorize;
  private boolean isSecurityEnabled;
  
  /**
   * The first four bytes of Hadoop RPC connections
   */
  public static final ByteBuffer HEADER = ByteBuffer.wrap("hrpc".getBytes());
  
  // 1 : Introduce ping and server does not throw away RPCs
  // 3 : Introduce the protocol into the RPC connection header
  // 4 : Introduced SASL security layer
  public static final byte CURRENT_VERSION = 4;
  
  /**
   * How many calls/handler are allowed in the queue.
   */
  private static final int IPC_SERVER_HANDLER_QUEUE_SIZE_DEFAULT = 100;
  private static final String  IPC_SERVER_HANDLER_QUEUE_SIZE_KEY = 
                                            "ipc.server.handler.queue.size";
  
  /**
   * Initial and max size of response buffer
   */
  static int INITIAL_RESP_BUF_SIZE = 10240;
  static final String IPC_SERVER_RPC_MAX_RESPONSE_SIZE_KEY = 
                        "ipc.server.max.response.size";
  static final int IPC_SERVER_RPC_MAX_RESPONSE_SIZE_DEFAULT = 1024*1024;
  
  public static final Log LOG = LogFactory.getLog(Server.class);
  private static final Log AUDITLOG = 
    LogFactory.getLog("SecurityLogger."+Server.class.getName());
  private static final String AUTH_FAILED_FOR = "Auth failed for ";
  private static final String AUTH_SUCCESSFULL_FOR = "Auth successfull for "; 

  private static final ThreadLocal<Server> SERVER = new ThreadLocal<Server>();

  private static final Map<String, Class<?>> PROTOCOL_CACHE = 
    new ConcurrentHashMap<String, Class<?>>();
  
  static Class<?> getProtocolClass(String protocolName, Configuration conf) 
  throws ClassNotFoundException {
    Class<?> protocol = PROTOCOL_CACHE.get(protocolName);
    if (protocol == null) {
      protocol = conf.getClassByName(protocolName);
      PROTOCOL_CACHE.put(protocolName, protocol);
    }
    return protocol;
  }
  
  /** Returns the server instance called under or null.  May be called under
   * {@link #call(Writable, long)} implementations, and under {@link Writable}
   * methods of paramters and return values.  Permits applications to access
   * the server context.*/
  public static Server get() {
    return SERVER.get();
  }
 
  /** This is set to Call object before Handler invokes an RPC and reset
   * after the call returns.
   */
  private static final ThreadLocal<Call> CurCall = new ThreadLocal<Call>();
  
  /** Returns the remote side ip address when invoked inside an RPC 
   *  Returns null incase of an error.
   */
  public static InetAddress getRemoteIp() {
    Call call = CurCall.get();
    if (call != null) {
      return call.connection.socket.getInetAddress();
    }
    return null;
  }
  /** Returns remote address as a string when invoked inside an RPC.
   *  Returns null in case of an error.
   */
  public static String getRemoteAddress() {
    InetAddress addr = getRemoteIp();
    return (addr == null) ? null : addr.getHostAddress();
  }

  private String bindAddress; 
  private int port;                               // port we listen on
  private int handlerCount;                       // number of handler threads
  private int readThreads;                        // Listener线程中readPool中reader线程的个数.ipc.server.read.threadpool.size default=1
  private Class<? extends Writable> paramClass;   // class of call parameters
  private int maxIdleTime;                        // the maximum idle time after 
                                                  // which a client may be disconnected
  private int thresholdIdleConnections;           // the number of idle connections
                                                  // after which we will start
                                                  // cleaning up idle 
                                                  // connections
  int maxConnectionsToNuke;                       // the max number of 
                                                  // connections to nuke
                                                  //during a cleanup
  
  protected RpcInstrumentation rpcMetrics;
  
  private Configuration conf;
  private SecretManager<TokenIdentifier> secretManager;

  private int maxQueueSize;
  private final int maxRespSize;//responseQueue的最大长度,参数ipc.server.handler.queue.size,default 1048576B(1 MB)
  private int socketSendBufferSize;//默认值是0
  private final boolean tcpNoDelay; //默认为false，即采用纳格算法,先buffer再发送. if T then disable Nagle's Algorithm

  volatile private boolean running = true; // Server的状态. true while server runs
  private BlockingQueue<Call> callQueue; // 这个是一个全局队列，存放client发来的Call请求.Listener负责入队,Handler负责处理/响应队元素
  
  private List<Connection> connectionList = //存放Server持有的所有connection.
    Collections.synchronizedList(new LinkedList<Connection>());
  //maintain a list
  //of client connections
  private Listener listener = null;
  private Responder responder = null;
  private int numConnections = 0;
  private Handler[] handlers = null;

  /**
   * A convenience method to bind to a given address and report 
   * better exceptions if the address is not a valid host.
   * @param socket the socket to bind
   * @param address the address to bind to
   * @param backlog the number of connections allowed in the queue
   * @throws BindException if the address can't be bound
   * @throws UnknownHostException if the address isn't a valid host name
   * @throws IOException other random errors from bind
   */
  public static void bind(ServerSocket socket, InetSocketAddress address, 
                          int backlog) throws IOException {
    try {
      socket.bind(address, backlog);
    } catch (BindException e) {
      BindException bindException = new BindException("Problem binding to " + address
                                                      + " : " + e.getMessage());
      bindException.initCause(e);
      throw bindException;
    } catch (SocketException e) {
      // If they try to bind to a different host's address, give a better
      // error message.
      if ("Unresolved address".equals(e.getMessage())) {
        throw new UnknownHostException("Invalid hostname for server: " + 
                                       address.getHostName());
      } else {
        throw e;
      }
    }
  }
  
  /**
   * Returns a handle to the rpcMetrics (required in tests)
   * @return rpc metrics
   */
  public RpcInstrumentation getRpcMetrics() {
    return rpcMetrics;
  }
  /************************************************1.Server端的Call, 排队等待被处理(queued for handling)*****************************************************************/
  /** A call queued for handling. */
  private static class Call {
    private int id;                               //ID： the client's call id
    private Writable param;                       //参数: the parameter passed
    private Connection connection;                //连接: connection to client
    private long timestamp;         // the time received when response is null
                                    // the time served when response is not null
    private ByteBuffer response;    //这个call的响应信息. the response for this call
    /**构造函数*/
    public Call(int id, Writable param, Connection connection) { 
      this.id = id;
      this.param = param;
      this.connection = connection;
      this.timestamp = System.currentTimeMillis();
      this.response = null;
    }
    
    @Override
    public String toString() {
      return param.toString() + " from " + connection.toString();
    }

    public void setResponse(ByteBuffer response) {
      this.response = response;
    }
  }
  /*********************************2.Listener 类**********************************************************************************************************/
  /** Listens on the socket. Creates jobs for the handler threads*/
  private class Listener extends Thread {
    
    private ServerSocketChannel acceptChannel = null; //the accept channel
    private Selector selector = null; //the selector that we use for the server
    private Reader[] readers = null;
    private int currentReader = 0;
    private InetSocketAddress address; //the address we bind at
    private Random rand = new Random();
    private long lastCleanupRunTime = 0; //the last time when a cleanup connec-
                                         //-tion (for idle connections) ran
    private long cleanupInterval = 10000; //the minimum interval between 
                                          //two cleanup runs
    private int backlogLength = conf.getInt("ipc.server.listen.queue.size", 128);
    private ExecutorService readPool; 
    /**Listener的构造函数,Server的listener成员是通过该构造函数创建的*/
    public Listener() throws IOException {
      address = new InetSocketAddress(bindAddress, port);//1.根据Server的bindAddress和port,构建address
      // Create a new server socket and set to non blocking mode
      acceptChannel = ServerSocketChannel.open();		 //2.创建一个server socket,设置为NIO模式
      acceptChannel.configureBlocking(false);

      //Bind the server socket to the local host and port//3.将该server socket绑定address
      bind(acceptChannel.socket(), address, backlogLength);
      port = acceptChannel.socket().getLocalPort(); //这个是Server.Listener进程侦听的端口,如Namenode进程的Listener线程侦听8020. Could be an ephemeral port
      // create a selector;								 //4.创建一个通道管理器
      selector= Selector.open();
      readers = new Reader[readThreads];				 //5.创建一组readers线程,每个reader持有一个通道管理器readSelector
      readPool = Executors.newFixedThreadPool(readThreads);//6.初始化reader线程池，当前版本的RPC里并没有用到readPool,而是用readers数组获取reader线程的。
      for (int i = 0; i < readThreads; i++) {
        Selector readSelector = Selector.open();
        Reader reader = new Reader(readSelector);
        readers[i] = reader;
        readPool.execute(reader);
      }
      													//7.注册OP_ACCEPT事件,以响应Client端的连接请求
      // Register accepts on the server socket with the selector.
      acceptChannel.register(selector, SelectionKey.OP_ACCEPT);
      this.setName("IPC Server listener on " + port);	//8.设置为Daemon线程
      this.setDaemon(true);
    }
    
    private class Reader implements Runnable {
      private volatile boolean adding = false;//flag,是否正在注册事件
      private Selector readSelector = null;	  //通道管理器

      Reader(Selector readSelector) {
        this.readSelector = readSelector;
      }
      public void run() {
        LOG.info("Starting SocketReader");
        synchronized (this) {
          while (running) {
            SelectionKey key = null;
            try {
              readSelector.select();//1.阻塞等待OP_READ事件到来
              while (adding) {
                this.wait(1000);
              }              
              						//2.处理OP_READ事件
              Iterator<SelectionKey> iter = readSelector.selectedKeys().iterator();
              while (iter.hasNext()) {
                key = iter.next();	//(2.1)取一个OP_READ事件
                iter.remove();
                if (key.isValid()) {//(2.2)执行Server.Listener.doRead(key)
                  if (key.isReadable()) {
                    doRead(key);
                  }
                }
                key = null;
              }
            } catch (InterruptedException e) {
              if (running) {                      // unexpected -- log it
                LOG.info(getName() + " caught: " +
                         StringUtils.stringifyException(e));
              }
            } catch (IOException ex) {
              LOG.error("Error in Reader", ex);
            }
          }
        }
      }

      /**
       * This gets reader into the state that waits for the new channel
       * to be registered with readSelector. If it was waiting in select()
       * the thread will be woken up, otherwise whenever select() is called
       * it will return even if there is nothing to read and wait
       * in while(adding) for finishAdd call
       */
      public void startAdd() {
        adding = true;
        readSelector.wakeup();
      }
      
      public synchronized SelectionKey registerChannel(SocketChannel channel)
                                                          throws IOException {
          return channel.register(readSelector, SelectionKey.OP_READ);
      }

      public synchronized void finishAdd() {
        adding = false;
        this.notify();        
      }
    }

    /** cleanup connections from connectionList. Choose a random range
     * to scan and also have a limit on the number of the connections
     * that will be cleanedup per run. The criteria for cleanup is the time
     * for which the connection was idle. If 'force' is true then all 
     * connections will be looked at for the cleanup.
     */
    private void cleanupConnections(boolean force) {
      if (force || numConnections > thresholdIdleConnections) {
        long currentTime = System.currentTimeMillis();
        if (!force && (currentTime - lastCleanupRunTime) < cleanupInterval) {
          return;
        }
        int start = 0;
        int end = numConnections - 1;
        if (!force) {
          start = rand.nextInt() % numConnections;
          end = rand.nextInt() % numConnections;
          int temp;
          if (end < start) {
            temp = start;
            start = end;
            end = temp;
          }
        }
        int i = start;
        int numNuked = 0;
        while (i <= end) {
          Connection c;
          synchronized (connectionList) {
            try {
              c = connectionList.get(i);
            } catch (Exception e) {return;}
          }
          if (c.timedOut(currentTime)) {
            if (LOG.isDebugEnabled())
              LOG.debug(getName() + ": disconnecting client " + c.getHostAddress());
            closeConnection(c);
            numNuked++;
            end--;
            c = null;
            if (!force && numNuked == maxConnectionsToNuke) break;
          }
          else i++;
        }
        lastCleanupRunTime = System.currentTimeMillis();
      }
    }
    /*****pc.Server.Listener.run()方法,Listener构造函数中创建了server socket，并将channel的OP_ACCEPT事件注册到selector,run()中等待注册事件并处理*/
    @Override
    public void run() {
      LOG.info(getName() + ": starting");
      SERVER.set(Server.this);
      while (running) {
        SelectionKey key = null;
        try {
          selector.select();		//1.阻塞等待OP_ACCEPT事件到来
          Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
          while (iter.hasNext()) {	//2.处理每个OP_ACCEPT事件(也即Client发来的连接请求)
            key = iter.next();		//(2.1)取出一个连接请求
            iter.remove();
            try {
              if (key.isValid()) {
                if (key.isAcceptable())
                  doAccept(key);   //(2.2)交给doAccept处理:
              }
            } catch (IOException e) {
            }
            key = null;
          }
        } catch (OutOfMemoryError e) {
          // we can run out of memory if we have too many threads
          // log the event and sleep for a minute and give 
          // some thread(s) a chance to finish
          LOG.warn("Out of Memory in server select", e);
          closeCurrentConnection(key, e);
          cleanupConnections(true);
          try { Thread.sleep(60000); } catch (Exception ie) {}
        } catch (Exception e) {
          closeCurrentConnection(key, e);
        }
        cleanupConnections(false);
      }
      LOG.info("Stopping " + this.getName());

      synchronized (this) {
        try {
          acceptChannel.close();
          selector.close();
        } catch (IOException e) { }

        selector= null;
        acceptChannel= null;
        
        // clean up all connections
        while (!connectionList.isEmpty()) {
          closeConnection(connectionList.remove(0));
        }
      }
    }

    private void closeCurrentConnection(SelectionKey key, Throwable e) {
      if (key != null) {
        Connection c = (Connection)key.attachment();
        if (c != null) {
          if (LOG.isDebugEnabled())
            LOG.debug(getName() + ": disconnecting client " + c.getHostAddress());
          closeConnection(c);
          c = null;
        }
      }
    }

    InetSocketAddress getAddress() {
      return (InetSocketAddress)acceptChannel.socket().getLocalSocketAddress();
    }/**处理Client发来的连接请求事件：建立连接,将连接通道channel上的OP_READ事件注册到reader.readselector.
      *将代表此类注册的readkey和通道channel封装成connection，并将readKey的附属对象设为connection*/
    void doAccept(SelectionKey key) throws IOException,  OutOfMemoryError {
      Connection c = null;
      ServerSocketChannel server = (ServerSocketChannel) key.channel();
      SocketChannel channel;
      while ((channel = server.accept()) != null) {//如果成功与Client建立连接
        channel.configureBlocking(false);			//1.将连接通道设置为非阻塞
        channel.socket().setTcpNoDelay(tcpNoDelay); //2.设置socket参数
        Reader reader = getReader();				//3.从reader池中取一个reader线程
        try {
          reader.startAdd();						//4.将reader状态设置为”开始注册“
          SelectionKey readKey = reader.registerChannel(channel);		   //5.将该channel上的OP_READ事件注册到reader.readSelector
          c = new Connection(readKey, channel, System.currentTimeMillis());//6.根据readKey和channel构建一个Connection对象
          readKey.attach(c);											   //7.将该Connection设置为readKey的附属对象(attachement)
          synchronized (connectionList) {								   //8.将该connection放入connectionList列表
            connectionList.add(numConnections, c);
            numConnections++;
          }
          if (LOG.isDebugEnabled())
            LOG.debug("Server connection from " + c.toString() +
                "; # active connections: " + numConnections +
                "; # queued calls: " + callQueue.size());          
        } finally {
          reader.finishAdd(); 											  //9.将reader状态设置为"结束注册"
        }

      }
    }
    /**reader线程处理读取事件：获取所属的connection对象,调用该对象的readAndProcess处理Client发来的数据流*/
    void doRead(SelectionKey key) throws InterruptedException {
      int count = 0;
      Connection c = (Connection)key.attachment();//1.读取key附属的Connection对象
      if (c == null) {
        return;  
      }											  //2.刷新最近访问时间
      c.setLastContact(System.currentTimeMillis());
      
      try {
        count = c.readAndProcess();				  //3.读取并处理
      } catch (InterruptedException ieo) {
        LOG.info(getName() + ": readAndProcess caught InterruptedException", ieo);
        throw ieo;
      } catch (Exception e) {
        LOG.info(getName() + ": readAndProcess threw exception " + e + ". Count of bytes read: " + count, e);
        count = -1; //so that the (count < 0) block is executed
      }
      if (count < 0) {
        if (LOG.isDebugEnabled())
          LOG.debug(getName() + ": disconnecting client " + 
                    c + ". Number of active connections: "+
                    numConnections);
        closeConnection(c);
        c = null;
      }
      else {
        c.setLastContact(System.currentTimeMillis());
      }
    }   

    synchronized void doStop() {
      if (selector != null) {
        selector.wakeup();
        Thread.yield();
      }
      if (acceptChannel != null) {
        try {
          acceptChannel.socket().close();
        } catch (IOException e) {
          LOG.info(getName() + ":Exception in closing listener socket. " + e);
        }
      }
      readPool.shutdown();
    }

    // The method that will return the next reader to work with
    // Simplistic implementation of round robin for now
    Reader getReader() {
      currentReader = (currentReader + 1) % readers.length;
      return readers[currentReader];
    }

  }
  /**********************************************3.Responder 类,负责将Server对RPC的响应信息发送给client****************************************************************************/
  // Sends responses of RPC back to clients.
  private class Responder extends Thread {
    private Selector writeSelector;//通道管理器
    private int pending;         // 等待注册的连接数
    
    final static int PURGE_INTERVAL = 900000; // call的失效时间，15mins
    /**Reponder构造函数:将Reponder设置为Daemon线程,初始化writeSelector*/
    Responder() throws IOException {
      this.setName("IPC Server Responder");
      this.setDaemon(true);
      writeSelector = Selector.open(); //初始化通道管理器.
      pending = 0;
    }
    /**Reponder启动后的工作内容:*/
    @Override
    public void run() {
      LOG.info(getName() + ": starting");
      SERVER.set(Server.this);
      long lastPurgeTime = 0;   // last check for old calls.
      
      while (running) {
        try {
          waitPending();     //(1).If a channel is being registered, wait.
          writeSelector.select(PURGE_INTERVAL);//(2).从writeSelector获取一组事件/key.
          Iterator<SelectionKey> iter = writeSelector.selectedKeys().iterator();
          while (iter.hasNext()) {
            SelectionKey key = iter.next();
            iter.remove();
            try {//(3)如果是SelectionKey.OP_WRITE事件,也即“channel准备好被写入数据了”.
              if (key.isValid() && key.isWritable()) {
                  doAsyncWrite(key);//写过程为：doAsyncWrite(key)->processResponse(call.connection.responseQueue)->channelWrite(call.channel, call.response)
              }
            } catch (IOException e) {
              LOG.info(getName() + ": doAsyncWrite threw exception " + e);
            }
          }//(4).清理过时的call
          long now = System.currentTimeMillis();
          if (now < lastPurgeTime + PURGE_INTERVAL) {
            continue;
          }
          lastPurgeTime = now;
          //
          // If there were some calls that have not been sent out for a
          // long time, discard them.
          //
          LOG.debug("Checking for old call responses.");
          ArrayList<Call> calls;
          
          // get the list of channels from list of keys.
          synchronized (writeSelector.keys()) {
            calls = new ArrayList<Call>(writeSelector.keys().size());
            iter = writeSelector.keys().iterator();
            while (iter.hasNext()) {
              SelectionKey key = iter.next();
              Call call = (Call)key.attachment();
              if (call != null && key.channel() == call.connection.channel) { 
                calls.add(call);
              }
            }
          }
          
          for(Call call : calls) {
            try {
              doPurge(call, now);
            } catch (IOException e) {
              LOG.warn("Error in purging old calls " + e);
            }
          }
        } catch (OutOfMemoryError e) {
          //
          // we can run out of memory if we have too many threads
          // log the event and sleep for a minute and give
          // some thread(s) a chance to finish
          //
          LOG.warn("Out of Memory in server select", e);
          try { Thread.sleep(60000); } catch (Exception ie) {}
        } catch (Exception e) {
          LOG.warn("Exception in Responder " + 
                   StringUtils.stringifyException(e));
        }
      }
      LOG.info("Stopping " + this.getName());
    }
    /***/
    private void doAsyncWrite(SelectionKey key) throws IOException {
      Call call = (Call)key.attachment(); //获取事件key附带的call
      if (call == null) {
        return;
      }
      if (key.channel() != call.connection.channel) {
        throw new IOException("doAsyncWrite: bad channel");
      }
      									  //处理call.connection.responseQueue的队首元素call,将call.response写入通道call.connection.channel
      synchronized(call.connection.responseQueue) {
        if (processResponse(call.connection.responseQueue, false)) {
          try {
            key.interestOps(0);
          } catch (CancelledKeyException e) {
            /* The Listener/reader might have closed the socket.
             * We don't explicitly cancel the key, so not sure if this will
             * ever fire.
             * This warning could be removed.
             */
            LOG.warn("Exception while changing ops : " + e);
          }
        }
      }
    }

    //
    // Remove calls that have been pending in the responseQueue 
    // for a long time.
    //
    private void doPurge(Call call, long now) throws IOException {
      LinkedList<Call> responseQueue = call.connection.responseQueue;
      synchronized (responseQueue) {
        Iterator<Call> iter = responseQueue.listIterator(0);
        while (iter.hasNext()) {
          call = iter.next();
          if (now > call.timestamp + PURGE_INTERVAL) {
            closeConnection(call.connection);
            break;
          }
        }
      }
    }

    // Processes one response. Returns true if there are no more pending data for this channel.
    //处理call.connection.responseQueue中的所有的call元素,将每个call元素的response写入channel
    /**将call.connection.reponse写入call.connection.channel**/
    private boolean processResponse(LinkedList<Call> responseQueue,
                                    boolean inHandler) throws IOException {
      boolean error = true;
      boolean done = false;       // there is more data for this channel.
      int numElements = 0;
      Call call = null;
      try {
        synchronized (responseQueue) {
          // (1).如果reponseQueue里没有元素，则任务已经完成
          // If there are no items for this channel, then we are done
          //
          numElements = responseQueue.size();
          if (numElements == 0) {
            error = false;
            return true;              // no more data for this channel.
          }
          // (2).从responseQueue中检出第一个call
          // Extract the first call
          //
          call = responseQueue.removeFirst();
          SocketChannel channel = call.connection.channel;
          if (LOG.isDebugEnabled()) {
            LOG.debug(getName() + ": responding to #" + call.id + " from " +
                      call.connection);
          }
          // (3)将数据call.reponse写入call.connection.channel，最多能写多少就写多少
          // Send as much data as we can in the non-blocking fashion
          //
          int numBytes = channelWrite(channel, call.response);
          if (numBytes < 0) {//写入数据长度为0，完成任务，返回true.
            return true;
          }//(4).1 call.reponse数组里的数据已经完全写入call.connection.channel了
          if (!call.response.hasRemaining()) {
            call.connection.decRpcCount();//call.connection的引用数减1.
            if (numElements == 1) {    //如果reponseQueue队列长度为1，说明本次写的是responseQueue中的最后一个元素，任务完成,done=true.
              done = true;             //no more data for this channel.
            } else {
              done = false;            //否则，done=false.
            }
            if (LOG.isDebugEnabled()) {
              LOG.debug(getName() + ": responding to #" + call.id + " from " +
                        call.connection + " Wrote " + numBytes + " bytes.");
            }
          } else {//(4).2 call.reponse数组里的数据没有全部写入call.connection.channel(数据太大了)
            //
            // If we were unable to write the entire response out, then insert in Selector queue.
            // 我们没有将call.reponse数组整个写入call.connection.channel, 需要将这个call重新添加到reponseQueue, 
            // 等待responder线程读取到OP_WRITE事件后，将call.response写入通道.
            call.connection.responseQueue.addFirst(call);
            // 如果本次processResponse是由Handler线程发起的，
            if (inHandler) {
              //
              call.timestamp = System.currentTimeMillis();
              
              incPending();//正在向writeSelector注册通道的线程+1；
              try {
                // Wakeup the thread blocked on select, only then can the call to channel.register() complete.
                // 唤醒正在调用writeSelector.select()阻塞等待事件的线程,select()立即返回;只有这样，才能调用channel.register()方法完成事件注册.
                writeSelector.wakeup();
                channel.register(writeSelector, SelectionKey.OP_WRITE, call);//将channel上的OP_WRITE事件注册到responder.writeSelector,call作为附属对象
              } catch (ClosedChannelException e) {
                //Its ok. channel might be closed else where.
                done = true;
              } finally {
                decPending();//正在向writeSelector注册通道的线程-1；
              }
            }
            if (LOG.isDebugEnabled()) {
              LOG.debug(getName() + ": responding to #" + call.id + " from " +
                        call.connection + " Wrote partial " + numBytes + 
                        " bytes.");
            }
          }
          error = false;              // everything went off well
        }
      } finally {
        if (error && call != null) {
          LOG.warn(getName()+", call " + call + ": output error");
          done = true;               // error. no more data for this channel.
          closeConnection(call.connection);
        }
      }
      return done;
    }
    
    //Enqueue a response from the application.
    /**对一个client发来的call进行处理后，结果已经写入call.response. 通过doRespond()方法,
     * 将该call添加到call.connection.responseQueue中,交给processResponse方法处理*/
    void doRespond(Call call) throws IOException {
      synchronized (call.connection.responseQueue) {
        call.connection.responseQueue.addLast(call);//入队列
        if (call.connection.responseQueue.size() == 1) {//如果size为1，说明是首次入队，交给processResponse处理。
          processResponse(call.connection.responseQueue, true);
        }
      }
    }

    private synchronized void incPending() {   // call waiting to be enqueued.
      pending++;
    }
    /**该方法会调用notify()唤醒waitPending()*/
    private synchronized void decPending() { // call done enqueueing.
      pending--;
      notify();
    }
    /**pending > 0,说明有call正在被注册到writeSelector，执行wait().等待被decPending()唤醒*/
    private synchronized void waitPending() throws InterruptedException {
      while (pending > 0) {
        wait();
      }
    }
  }/**********************************************************End of Responder*************************************************************************/
  /***********************************************************4.ipc Server端的Connection类,从connection读取calls并组成queue,以便于handler处理*****************************/
  /** Reads calls from a connection and queues them for handling. */
  public class Connection {
    private boolean rpcHeaderRead = false; // rpc头是否已读.if initial rpc header is read
    private boolean headerRead = false;    // connection头是否已读.if the connection header that follows version is read.
                                           // 

    private SocketChannel channel;		   //该connection的通道
    private ByteBuffer data;			   //
    private ByteBuffer dataLengthBuffer;   //
    private LinkedList<Call> responseQueue;//响应队列
    private volatile int rpcCount = 0;     // 未处理的rpc请求？？？？.number of outstanding rpcs
    private long lastContact;			   // 
    private int dataLength;				   //
    private Socket socket;				   //该connection的socket. socket=channel.socket()
    // Cache the remote host & port info so that even if the socket is disconnected, we can say where it used to connect to.
    // 缓存远程主机和端口信息,以便即使socket已经断开时，我们也能知道该connection曾连接到哪个地址
    private String hostAddress;
    private int remotePort;
    private InetAddress addr;
    
    ConnectionHeader header = new ConnectionHeader();//connection头
    Class<?> protocol;								 //协议类	
    boolean useSasl;									
    SaslServer saslServer;
    private AuthMethod authMethod;
    private boolean saslContextEstablished;
    private boolean skipInitialSaslHandshake;
    private ByteBuffer rpcHeaderBuffer;
    private ByteBuffer unwrappedData;
    private ByteBuffer unwrappedDataLengthBuffer;
    
    UserGroupInformation user = null;
    public UserGroupInformation attemptingUser = null; // user name before auth
    // Fake 'call' for failed authorization response
    // 在响应认证失败时使用的伪造call
    private final int AUTHROIZATION_FAILED_CALLID = -1;
    private final Call authFailedCall = 
      new Call(AUTHROIZATION_FAILED_CALLID, null, this);
    private ByteArrayOutputStream authFailedResponse = new ByteArrayOutputStream();
    // Fake 'call' for SASL context setup
    private static final int SASL_CALLID = -33;
    private final Call saslCall = new Call(SASL_CALLID, null, this);
    private final ByteArrayOutputStream saslResponse = new ByteArrayOutputStream();
    
    private boolean useWrap = false;
    /**构造函数,根据将SocketChannel对象封装成一个Connection对象*/
    public Connection(SelectionKey key, SocketChannel channel, 
                      long lastContact) {
      this.channel = channel;
      this.lastContact = lastContact;
      this.data = null;
      this.dataLengthBuffer = ByteBuffer.allocate(4);
      this.unwrappedData = null;
      this.unwrappedDataLengthBuffer = ByteBuffer.allocate(4);
      this.socket = channel.socket();
      this.addr = socket.getInetAddress();
      if (addr == null) {
        this.hostAddress = "*Unknown*";
      } else {
        this.hostAddress = addr.getHostAddress();
      }
      this.remotePort = socket.getPort();
      this.responseQueue = new LinkedList<Call>();
      if (socketSendBufferSize != 0) {
        try {
          socket.setSendBufferSize(socketSendBufferSize);
        } catch (IOException e) {
          LOG.warn("Connection: unable to set socket send buffer size to " +
                   socketSendBufferSize);
        }
      }
    }   

    @Override
    public String toString() {
      return getHostAddress() + ":" + remotePort; 
    }
    
    public String getHostAddress() {
      return hostAddress;
    }

    public InetAddress getHostInetAddress() {
      return addr;
    }
    
    public void setLastContact(long lastContact) {
      this.lastContact = lastContact;
    }

    public long getLastContact() {
      return lastContact;
    }

    /** Return true if the connection has no outstanding rpc */
    private boolean isIdle() {
      return rpcCount == 0;
    }
    
    /** Decrement the outstanding RPC count */
    private void decRpcCount() {
      rpcCount--;
    }
    
    /** Increment the outstanding RPC count */
    private void incRpcCount() {
      rpcCount++;
    }
    /**判断该connection是否超时*/
    private boolean timedOut(long currentTime) {
      if (isIdle() && currentTime -  lastContact > maxIdleTime)
        return true;
      return false;
    }
    
    private UserGroupInformation getAuthorizedUgi(String authorizedId)
        throws IOException {
      if (authMethod == SaslRpcServer.AuthMethod.DIGEST) {
        TokenIdentifier tokenId = SaslRpcServer.getIdentifier(authorizedId,
            secretManager);
        UserGroupInformation ugi = tokenId.getUser();
        if (ugi == null) {
          throw new AccessControlException(
              "Can't retrieve username from tokenIdentifier.");
        }
        ugi.addTokenIdentifier(tokenId);
        return ugi;
      } else {
        return UserGroupInformation.createRemoteUser(authorizedId);
      }
    }

    private void saslReadAndProcess(byte[] saslToken) throws IOException,
        InterruptedException {
      if (!saslContextEstablished) {
        byte[] replyToken = null;
        try {
          if (saslServer == null) {
            switch (authMethod) {
            case DIGEST:
              if (secretManager == null) {
                throw new AccessControlException(
                    "Server is not configured to do DIGEST authentication.");
              }
              saslServer = Sasl.createSaslServer(AuthMethod.DIGEST
                  .getMechanismName(), null, SaslRpcServer.SASL_DEFAULT_REALM,
                  SaslRpcServer.SASL_PROPS, new SaslDigestCallbackHandler(
                      secretManager, this));
              break;
            default:
              UserGroupInformation current = UserGroupInformation
                  .getCurrentUser();
              String fullName = current.getUserName();
              if (LOG.isDebugEnabled())
                LOG.debug("Kerberos principal name is " + fullName);
              final String names[] = SaslRpcServer.splitKerberosName(fullName);
              if (names.length != 3) {
                throw new AccessControlException(
                    "Kerberos principal name does NOT have the expected "
                        + "hostname part: " + fullName);
              }
              current.doAs(new PrivilegedExceptionAction<Object>() {
                @Override
                public Object run() throws SaslException {
                  saslServer = Sasl.createSaslServer(AuthMethod.KERBEROS
                      .getMechanismName(), names[0], names[1],
                      SaslRpcServer.SASL_PROPS, new SaslGssCallbackHandler());
                  return null;
                }
              });
            }
            if (saslServer == null)
              throw new AccessControlException(
                  "Unable to find SASL server implementation for "
                      + authMethod.getMechanismName());
            if (LOG.isDebugEnabled())
              LOG.debug("Created SASL server with mechanism = "
                  + authMethod.getMechanismName());
          }
          if (LOG.isDebugEnabled())
            LOG.debug("Have read input token of size " + saslToken.length
                + " for processing by saslServer.evaluateResponse()");
          replyToken = saslServer.evaluateResponse(saslToken);
        } catch (IOException e) {
          IOException sendToClient = e;
          Throwable cause = e;
          while (cause != null) {
            if (cause instanceof InvalidToken) {
              sendToClient = (InvalidToken) cause;
              break;
            }
            cause = cause.getCause();
          }
          doSaslReply(SaslStatus.ERROR, null, sendToClient.getClass().getName(), 
              sendToClient.getLocalizedMessage());
          rpcMetrics.incrAuthenticationFailures();
          String clientIP = this.toString();
          // attempting user could be null
          AUDITLOG.warn(AUTH_FAILED_FOR + clientIP + ":" + attemptingUser);
          throw e;
        }
        if (replyToken != null) {
          if (LOG.isDebugEnabled())
            LOG.debug("Will send token of size " + replyToken.length
                + " from saslServer.");
          doSaslReply(SaslStatus.SUCCESS, new BytesWritable(replyToken), null,
              null);
        }
        if (saslServer.isComplete()) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("SASL server context established. Negotiated QoP is "
                + saslServer.getNegotiatedProperty(Sasl.QOP));
          }
          String qop = (String) saslServer.getNegotiatedProperty(Sasl.QOP);
          useWrap = qop != null && !"auth".equalsIgnoreCase(qop);
          user = getAuthorizedUgi(saslServer.getAuthorizationID());
          if (LOG.isDebugEnabled()) {
            LOG.debug("SASL server successfully authenticated client: " + user);
          }
          rpcMetrics.incrAuthenticationSuccesses();
          AUDITLOG.info(AUTH_SUCCESSFULL_FOR + user);
          saslContextEstablished = true;
        }
      } else {
        if (LOG.isDebugEnabled())
          LOG.debug("Have read input token of size " + saslToken.length
              + " for processing by saslServer.unwrap()");
        
        if (!useWrap) {
          processOneRpc(saslToken);
        } else {
          byte[] plaintextData = saslServer.unwrap(saslToken, 0,
              saslToken.length);
          processUnwrappedData(plaintextData);
        }
      }
    }
    
    private void doSaslReply(SaslStatus status, Writable rv,
        String errorClass, String error) throws IOException {
      saslResponse.reset();
      DataOutputStream out = new DataOutputStream(saslResponse);
      out.writeInt(status.state); // write status
      if (status == SaslStatus.SUCCESS) {
        rv.write(out);
      } else {
        WritableUtils.writeString(out, errorClass);
        WritableUtils.writeString(out, error);
      }
      saslCall.setResponse(ByteBuffer.wrap(saslResponse.toByteArray()));
      responder.doRespond(saslCall);
    }
    
    private void disposeSasl() {
      if (saslServer != null) {
        try {
          saslServer.dispose();
        } catch (SaslException ignored) {
        }
      }
    }
    /**从ip.Server与Client建立的连接通道channel中读取数据流并处理*/
    public int readAndProcess() throws IOException, InterruptedException {
      while (true) {
        /* Read at most one RPC. If the header is not read completely yet then iterate until we read first RPC or until there is no data left.
         * 最多读取一个RPC. 如果header没有全部读完,则迭代读取，直至读取到第一个RPC或没有数据可读了.
         */    
        int count = -1;
        if (dataLengthBuffer.remaining() > 0) {//1.从流中读取4个字节的数据
          count = channelRead(channel, dataLengthBuffer); //      
          if (count < 0 || dataLengthBuffer.remaining() > 0)//
            return count;
        }
      
        if (!rpcHeaderRead) {				  //2.如果rpcHeaderRead为false，说明这4个字节属于RPC头
          //Every connection is expected to send the header.
          if (rpcHeaderBuffer == null) {	  //(2.1)由于RPC头有6个字节:hrpc|version|authorized ,采用(dataLengthBuffer 4字节)+(rpcHeaderBuffer 2字节)表示.
            rpcHeaderBuffer = ByteBuffer.allocate(2);
          }
          count = channelRead(channel, rpcHeaderBuffer);
          if (count < 0 || rpcHeaderBuffer.remaining() > 0) {
            return count;
          }									  //(2.2) 验证RPC前4字节是否为"hrpc",以及RPC版本是否一致,是否启用了安全认证
          int version = rpcHeaderBuffer.get(0);//获取RPC版本
          byte[] method = new byte[] {rpcHeaderBuffer.get(1)};//是否启用安全认证
          authMethod = AuthMethod.read(new DataInputStream(
              new ByteArrayInputStream(method)));
          dataLengthBuffer.flip();				          
          if (!HEADER.equals(dataLengthBuffer) || version != CURRENT_VERSION) {//验证
            //Warning is ok since this is not supposed to happen.
            LOG.warn("Incorrect header or version mismatch from " + 
                     hostAddress + ":" + remotePort +
                     " got version " + version + 
                     " expected version " + CURRENT_VERSION);
            return -1;
          }
          dataLengthBuffer.clear();
          if (authMethod == null) {
            throw new IOException("Unable to read authentication method");
          }									//3.认证相关的验证
          if (isSecurityEnabled && authMethod == AuthMethod.SIMPLE) {//(3.1)
            AccessControlException ae = new AccessControlException(
                "Authentication is required");
            setupResponse(authFailedResponse, authFailedCall, Status.FATAL,
                null, ae.getClass().getName(), ae.getMessage());
            responder.doRespond(authFailedCall);
            throw ae;
          }
          if (!isSecurityEnabled && authMethod != AuthMethod.SIMPLE) {//(3.2)
            doSaslReply(SaslStatus.SUCCESS, new IntWritable(
                SaslRpcServer.SWITCH_TO_SIMPLE_AUTH), null, null);
            authMethod = AuthMethod.SIMPLE;
            // client has already sent the initial Sasl message and we
            // should ignore it. Both client and server should fall back
            // to simple auth from now on.
            skipInitialSaslHandshake = true;
          }
          if (authMethod != AuthMethod.SIMPLE) {
            useSasl = true;
          }
          
          rpcHeaderBuffer = null;
          rpcHeaderRead = true;
          continue;
        }
        						  //4.读取数据流:dataLength,call.id,invocation
        if (data == null) {
          dataLengthBuffer.flip();//(4.1)读取数据长度
          dataLength = dataLengthBuffer.getInt();
          //数据长度为-1,是RPC PING请求				      
          if (dataLength == Client.PING_CALL_ID) {
            if(!useWrap) { //covers the !useSasl too
              dataLengthBuffer.clear();
              return 0;  //ping message
            }
          }//数据长度异常
          if (dataLength < 0) {
            LOG.warn("Unexpected data length " + dataLength + "!! from " + 
                getHostAddress());
          }					  //(4.2)给data分配相应长度的空间
          data = ByteBuffer.allocate(dataLength);
        }
        					 //5.读数据到data
        count = channelRead(channel, data);
        				     //6.处理data:data由call请求的id和invocation组成
        if (data.remaining() == 0) {
          dataLengthBuffer.clear();
          data.flip();
          if (skipInitialSaslHandshake) {
            data = null;
            skipInitialSaslHandshake = false;
            continue;
          }
          boolean isHeaderRead = headerRead;//headerRead的初始值是false,processOneRpc()方法可能会修改它的值
          if (useSasl) {
            saslReadAndProcess(data.array());
          } else {
            processOneRpc(data.array());//处理一条RPC call请求
          }
          data = null;
          if (!isHeaderRead) {		    //如果ConnectionHeader还没有读取完,则继续
            continue;
          }
        } 
        return count;
      }
    }

    /// Reads the connection header following version
    private void processHeader(byte[] buf) throws IOException {
      DataInputStream in =
        new DataInputStream(new ByteArrayInputStream(buf));
      header.readFields(in);//header是ConnectionHeader对象,它实现了Writable接口,readFields(in)能从流数据初始化该对象
      try {
        String protocolClassName = header.getProtocol();
        if (protocolClassName != null) {
          protocol = getProtocolClass(header.getProtocol(), conf);
        }
      } catch (ClassNotFoundException cnfe) {
        throw new IOException("Unknown protocol: " + header.getProtocol());
      }
      
      UserGroupInformation protocolUser = header.getUgi();
      if (!useSasl) {
        user = protocolUser;
        if (user != null) {
          user.setAuthenticationMethod(AuthMethod.SIMPLE.authenticationMethod);
        }
      } else {
        // user is authenticated
        user.setAuthenticationMethod(authMethod.authenticationMethod);
        //Now we check if this is a proxy user case. If the protocol user is
        //different from the 'user', it is a proxy user scenario. However, 
        //this is not allowed if user authenticated with DIGEST.
        if ((protocolUser != null)
            && (!protocolUser.getUserName().equals(user.getUserName()))) {
          if (authMethod == AuthMethod.DIGEST) {
            // Not allowed to doAs if token authentication is used
            throw new AccessControlException("Authenticated user (" + user
                + ") doesn't match what the client claims to be ("
                + protocolUser + ")");
          } else {
            // Effective user can be different from authenticated user
            // for simple auth or kerberos auth
            // The user is the real user. Now we create a proxy user
            UserGroupInformation realUser = user;
            user = UserGroupInformation.createProxyUser(protocolUser
                .getUserName(), realUser);
            // Now the user is a proxy user, set Authentication method Proxy.
            user.setAuthenticationMethod(AuthenticationMethod.PROXY);
          }
        }
      }
    }
    
    private void processUnwrappedData(byte[] inBuf) throws IOException,
        InterruptedException {
      ReadableByteChannel ch = Channels.newChannel(new ByteArrayInputStream(
          inBuf));
      // Read all RPCs contained in the inBuf, even partial ones
      while (true) {
        int count = -1;
        if (unwrappedDataLengthBuffer.remaining() > 0) {
          count = channelRead(ch, unwrappedDataLengthBuffer);
          if (count <= 0 || unwrappedDataLengthBuffer.remaining() > 0)
            return;
        }

        if (unwrappedData == null) {
          unwrappedDataLengthBuffer.flip();
          int unwrappedDataLength = unwrappedDataLengthBuffer.getInt();

          if (unwrappedDataLength == Client.PING_CALL_ID) {
            if (LOG.isDebugEnabled())
              LOG.debug("Received ping message");
            unwrappedDataLengthBuffer.clear();
            continue; // ping message
          }
          unwrappedData = ByteBuffer.allocate(unwrappedDataLength);
        }

        count = channelRead(ch, unwrappedData);
        if (count <= 0 || unwrappedData.remaining() > 0)
          return;

        if (unwrappedData.remaining() == 0) {
          unwrappedDataLengthBuffer.clear();
          unwrappedData.flip();
          processOneRpc(unwrappedData.array());
          unwrappedData = null;
        }
      }
    }
    /**处理一个RPC*/
    private void processOneRpc(byte[] buf) throws IOException,
        InterruptedException {
      if (headerRead) {//1.已经处理ConnectionHeader数据流，则接下来的是Client发来的call请求,调用processData(buf)处理
        processData(buf);
      } else {	       //2.还没处理ConnectionHeader数据流，用processHeader()处理ConnectionHeader
        processHeader(buf);			 //(2.1)处理连接头
        headerRead = true;
        if (!authorizeConnection()) {//(2.2)安全验证
          throw new AccessControlException("Connection from " + this
              + " for protocol " + header.getProtocol()
              + " is unauthorized for user " + user);
        }
      }
    }
    /**处理Client发来的call请求，由call.id和invocation组成*****************************/
    private void processData(byte[] buf) throws  IOException, InterruptedException {
      DataInputStream dis =
        new DataInputStream(new ByteArrayInputStream(buf));
      int id = dis.readInt();                    //1.读取client端发来的call请求的id
        
      if (LOG.isDebugEnabled())
        LOG.debug(" got #" + id);
      											 //2.读取all请求的参数: (param为Invocation对象)
      Writable param = ReflectionUtils.newInstance(paramClass, conf);//read param
      param.readFields(dis);  //从流里读取一个Invocation      
        
      Call call = new Call(id, param, this);	//3.根据client发来的Client.Call的id,和invocation及本connection对象, 初始化一个Server.Call对象
      callQueue.put(call);              		//4.将从RPC请求解析出的call对象放入callQueue队列. queue the call; maybe blocked here
      incRpcCount();  // 						//5.该connection待处理的rpc Call请求的个数+1
    }

    private boolean authorizeConnection() throws IOException {
      try {
        // If auth method is DIGEST, the token was obtained by the
        // real user for the effective user, therefore not required to
        // authorize real user. doAs is allowed only for simple or kerberos
        // authentication
        if (user != null && user.getRealUser() != null
            && (authMethod != AuthMethod.DIGEST)) {
          ProxyUsers.authorize(user, this.getHostAddress(), conf);
        }
        authorize(user, header, getHostInetAddress());
        if (LOG.isDebugEnabled()) {
          LOG.debug("Successfully authorized " + header);
        }
        rpcMetrics.incrAuthorizationSuccesses();
      } catch (AuthorizationException ae) {
        rpcMetrics.incrAuthorizationFailures();
        setupResponse(authFailedResponse, authFailedCall, Status.FATAL, null,
            ae.getClass().getName(), ae.getMessage());
        responder.doRespond(authFailedCall);
        return false;
      }
      return true;
    }
    
    private synchronized void close() throws IOException {
      disposeSasl();
      data = null;
      dataLengthBuffer = null;
      if (!channel.isOpen())
        return;
      try {socket.shutdownOutput();} catch(Exception e) {}
      if (channel.isOpen()) {
        try {channel.close();} catch(Exception e) {}
      }
      try {socket.close();} catch(Exception e) {}
    }
  }
  /****************5.Handler类，负责从callQueue中取call并响应**********************************************************/
  /** Handles queued calls . */
  private class Handler extends Thread {
    public Handler(int instanceNumber) {
      this.setDaemon(true);
      this.setName("IPC Server handler "+ instanceNumber + " on " + port);
    }

    @Override
    public void run() {
      LOG.info(getName() + ": starting");
      SERVER.set(Server.this);
      ByteArrayOutputStream buf = 
        new ByteArrayOutputStream(INITIAL_RESP_BUF_SIZE);
      while (running) {//Handler启动后就一直从callQueue中取call并处理/响应
        try {
          final Call call = callQueue.take(); //1.从callQueue中取出一个元素(可能阻塞)

          if (LOG.isDebugEnabled())
            LOG.debug(getName() + ": has #" + call.id + " from " +
                      call.connection);
          
          String errorClass = null;
          String error = null;
          Writable value = null;
            //2.将call设置为当前正在处理的call
          CurCall.set(call);
          try {
        	//3.通过Server.call()方法处理该call请求,返回值写入value
            // Make the call as the user via Subject.doAs, thus associating the call with the Subject
            if (call.connection.user == null) {
              value = call(call.connection.protocol, call.param, 
                           call.timestamp);
            } else {
              value = 
                call.connection.user.doAs
                  (new PrivilegedExceptionAction<Writable>() {
                     @Override
                     public Writable run() throws Exception {
                       // make the call
                       return call(call.connection.protocol, 
                                   call.param, call.timestamp);

                     }
                   }
                  );
            }
          } catch (Throwable e) {
            LOG.info(getName()+", call "+call+": error: " + e, e);
            errorClass = e.getClass().getName();
            error = StringUtils.stringifyException(e);
          }//4.将当前处理的call设置为null
          CurCall.set(null);							//5.将call的调用结果写入call.response，将call添加到connection的响应队列responseQueue的队尾，
          synchronized (call.connection.responseQueue) {// 并处理responseQueue的队首元素(将call.response写入call.connection.channel. 保证responseQueue是先进先出的队列
            //(5.1) 将call请求的返回值rv,以及状态等信息写入call.response
            // setupResponse() needs to be sync'ed together with responder.doResponse() since setupResponse may use
            // SASL to encrypt response data and SASL enforces
            // its own message ordering.  
            setupResponse(buf, call, 
                        (error == null) ? Status.SUCCESS : Status.ERROR, 
                        value, errorClass, error);
          // Discard the large buf and reset it back to 
          // smaller size to freeup heap
          if (buf.size() > maxRespSize) {
            LOG.warn("Large response size " + buf.size() + " for call " + 
                call.toString());
              buf = new ByteArrayOutputStream(INITIAL_RESP_BUF_SIZE);
            }//(5.2) 通过responder将call.connection.responseQueue的每个call元素的response信息写入call.connection.channel.
            responder.doRespond(call);
          }
        } catch (InterruptedException e) {
          if (running) {                          // unexpected -- log it
            LOG.info(getName() + " caught: " +
                     StringUtils.stringifyException(e));
          }
        } catch (Exception e) {
          LOG.info(getName() + " caught: " +
                   StringUtils.stringifyException(e));
        }
      }
      LOG.info(getName() + ": exiting");
    }

  }
  
  protected Server(String bindAddress, int port,
      Class<? extends Writable> paramClass, int handlerCount, 
      Configuration conf)
  throws IOException 
  {
    this(bindAddress, port, paramClass, handlerCount,  conf, Integer.toString(port), null);
  }

  protected Server(String bindAddress, int port,
      Class<? extends Writable> paramClass, int handlerCount, 
      Configuration conf, String serverName)
  throws IOException 
  {
    this(bindAddress, port, paramClass, handlerCount,  conf, serverName, null);
  }
  
  /** Constructs a server listening on the named port and address.  Parameters passed must
   * be of the named class.  The <code>handlerCount</handlerCount> determines
   * the number of handler threads that will be used to process calls.
   * 
   */
  @SuppressWarnings("unchecked")
  protected Server(String bindAddress, int port, 
                  Class<? extends Writable> paramClass, int handlerCount, 
                  Configuration conf, String serverName, SecretManager<? extends TokenIdentifier> secretManager) 
    throws IOException {
    this.bindAddress = bindAddress;
    this.conf = conf;
    this.port = port;
    this.paramClass = paramClass;
    this.handlerCount = handlerCount;
    this.socketSendBufferSize = 0;
    this.maxQueueSize = handlerCount * conf.getInt(
                                IPC_SERVER_HANDLER_QUEUE_SIZE_KEY,
                                IPC_SERVER_HANDLER_QUEUE_SIZE_DEFAULT);
    this.maxRespSize = conf.getInt(IPC_SERVER_RPC_MAX_RESPONSE_SIZE_KEY,
                                   IPC_SERVER_RPC_MAX_RESPONSE_SIZE_DEFAULT);
    this.readThreads = conf.getInt(
        IPC_SERVER_RPC_READ_THREADS_KEY,
        IPC_SERVER_RPC_READ_THREADS_DEFAULT);
    this.callQueue  = new LinkedBlockingQueue<Call>(maxQueueSize); 
    this.maxIdleTime = 2*conf.getInt("ipc.client.connection.maxidletime", 1000);
    this.maxConnectionsToNuke = conf.getInt("ipc.client.kill.max", 10);
    this.thresholdIdleConnections = conf.getInt("ipc.client.idlethreshold", 4000);
    this.secretManager = (SecretManager<TokenIdentifier>) secretManager;
    this.authorize = 
      conf.getBoolean(HADOOP_SECURITY_AUTHORIZATION, false);
    this.isSecurityEnabled = UserGroupInformation.isSecurityEnabled();
    
    // Start the listener here and let it bind to the port
    listener = new Listener();
    this.port = listener.getAddress().getPort();    
    this.rpcMetrics = RpcInstrumentation.create(serverName, this.port);
    this.tcpNoDelay = conf.getBoolean("ipc.server.tcpnodelay", false);

    // Create the responder here
    responder = new Responder();
    
    if (isSecurityEnabled) {
      SaslRpcServer.init(conf);
    }
  }

  private void closeConnection(Connection connection) {
    synchronized (connectionList) {
      if (connectionList.remove(connection))
        numConnections--;
    }
    try {
      connection.close();
    } catch (IOException e) {
    }
  }
  
  /**
   * Setup response for the IPC Call.
   * 将该call的id,status,以及调用的返回值rv写入response,并赋给将response赋给call.response
   * @param response buffer to serialize the response into
   * @param call {@link Call} to which we are setting up the response
   * @param status {@link Status} of the IPC call
   * @param rv return value for the IPC Call, if the call was successful
   * @param errorClass error class, if the the call failed
   * @param error error message, if the call failed
   * @throws IOException
   */
  private void setupResponse(ByteArrayOutputStream response, 
                             Call call, Status status, 
                             Writable rv, String errorClass, String error) 
  throws IOException {
    response.reset();
    DataOutputStream out = new DataOutputStream(response);
    out.writeInt(call.id);                // write call id
    out.writeInt(status.state);           // write status

    if (status == Status.SUCCESS) {
      rv.write(out);
    } else {
      WritableUtils.writeString(out, errorClass);
      WritableUtils.writeString(out, error);
    }
    if (call.connection.useWrap) {
      wrapWithSasl(response, call);
    }
    call.setResponse(ByteBuffer.wrap(response.toByteArray()));
  }
  
  private void wrapWithSasl(ByteArrayOutputStream response, Call call)
      throws IOException {
    if (call.connection.useSasl) {
      byte[] token = response.toByteArray();
      // synchronization may be needed since there can be multiple Handler
      // threads using saslServer to wrap responses.
      synchronized (call.connection.saslServer) {
        token = call.connection.saslServer.wrap(token, 0, token.length);
      }
      if (LOG.isDebugEnabled())
        LOG.debug("Adding saslServer wrapped token of size " + token.length
            + " as call response.");
      response.reset();
      DataOutputStream saslOut = new DataOutputStream(response);
      saslOut.writeInt(token.length);
      saslOut.write(token, 0, token.length);
    }
  }
  
  Configuration getConf() {
    return conf;
  }
  
  /** for unit testing only, should be called before server is started */ 
  void disableSecurity() {
    this.isSecurityEnabled = false;
  }
  
  /** for unit testing only, should be called before server is started */ 
  void enableSecurity() {
    this.isSecurityEnabled = true;
  }
  
  /** Sets the socket buffer size used for responding to RPCs */
  public void setSocketSendBufSize(int size) { this.socketSendBufferSize = size; }

  /** Starts the service.  Must be called before any calls will be handled. */
  public synchronized void start() {//RPC Ｓerver启动1个responder,1个listener,和多个handler.
    responder.start();
    listener.start();
    handlers = new Handler[handlerCount];
    
    for (int i = 0; i < handlerCount; i++) {
      handlers[i] = new Handler(i);
      handlers[i].start();
    }
  }

  /** Stops the service.  No new calls will be handled after this is called. */
  public synchronized void stop() {
    LOG.info("Stopping server on " + port);
    running = false;
    if (handlers != null) {
      for (int i = 0; i < handlerCount; i++) {
        if (handlers[i] != null) {
          handlers[i].interrupt();
        }
      }
    }
    listener.interrupt();
    listener.doStop();
    responder.interrupt();
    notifyAll();
    if (this.rpcMetrics != null) {
      this.rpcMetrics.shutdown();
    }
  }

  /** Wait for the server to be stopped.
   * Does not wait for all subthreads to finish.
   *  See {@link #stop()}.
   */
  public synchronized void join() throws InterruptedException {
    while (running) {
      wait();
    }
  }

  /**
   * Return the socket (ip+port) on which the RPC server is listening to.
   * @return the socket (ip+port) on which the RPC server is listening to.
   */
  public synchronized InetSocketAddress getListenerAddress() {
    return listener.getAddress();
  }
  
  /** 
   * Called for each call. 
   * @deprecated Use {@link #call(Class, Writable, long)} instead
   */
  @Deprecated
  public Writable call(Writable param, long receiveTime) throws IOException {
    return call(null, param, receiveTime);
  }
  
  /** Called for each call. */
  public abstract Writable call(Class<?> protocol,
                               Writable param, long receiveTime)
  throws IOException;
  
  /**
   * Authorize the incoming client connection.
   * 
   * @param user client user
   * @param connection incoming connection
   * @param addr InetAddress of incoming connection
   * @throws AuthorizationException when the client isn't authorized to talk the protocol
   */
  public void authorize(UserGroupInformation user, 
                        ConnectionHeader connection,
                        InetAddress addr
                        ) throws AuthorizationException {
    if (authorize) {
      Class<?> protocol = null;
      try {
        protocol = getProtocolClass(connection.getProtocol(), getConf());
      } catch (ClassNotFoundException cfne) {
        throw new AuthorizationException("Unknown protocol: " + 
                                         connection.getProtocol());
      }
      ServiceAuthorizationManager.authorize(user, protocol, getConf(), addr);
    }
  }
  
  /**
   * The number of open RPC conections
   * @return the number of open rpc connections
   */
  public int getNumOpenConnections() {
    return numConnections;
  }
  
  /**
   * The number of rpc calls in the queue.
   * @return The number of rpc calls in the queue.
   */
  public int getCallQueueLen() {
    return callQueue.size();
  }
  
  
  /**
   * When the read or write buffer size is larger than this limit, i/o will be 
   * done in chunks of this size. Most RPC requests and responses would be
   * be smaller.
   */
  private static int NIO_BUFFER_LIMIT = 8*1024; //should not be more than 64KB.
  
  /**
   * This is a wrapper around {@link WritableByteChannel#write(ByteBuffer)}.
   * If the amount of data is large, it writes to channel in smaller chunks. 
   * This is to avoid jdk from creating many direct buffers as the size of 
   * buffer increases. This also minimizes extra copies in NIO layer
   * as a result of multiple write operations required to write a large 
   * buffer.  
   *
   * @see WritableByteChannel#write(ByteBuffer)
   */
  private int channelWrite(WritableByteChannel channel, 
                           ByteBuffer buffer) throws IOException {
    
    int count =  (buffer.remaining() <= NIO_BUFFER_LIMIT) ?
                 channel.write(buffer) : channelIO(null, channel, buffer);
    if (count > 0) {
      rpcMetrics.incrSentBytes(count);
    }
    return count;
  }
  
  
  /**
   * This is a wrapper around {@link ReadableByteChannel#read(ByteBuffer)}.
   * If the amount of data is large, it writes to channel in smaller chunks. 
   * This is to avoid jdk from creating many direct buffers as the size of 
   * ByteBuffer increases. There should not be any performance degredation.
   * 
   * @see ReadableByteChannel#read(ByteBuffer)
   */
  private int channelRead(ReadableByteChannel channel, 
                          ByteBuffer buffer) throws IOException {
    
    int count = (buffer.remaining() <= NIO_BUFFER_LIMIT) ?
                channel.read(buffer) : channelIO(channel, null, buffer);
    if (count > 0) {
      rpcMetrics.incrReceivedBytes(count);
    }
    return count;
  }
  
  /**
   * Helper for {@link #channelRead(ReadableByteChannel, ByteBuffer)}
   * and {@link #channelWrite(WritableByteChannel, ByteBuffer)}. Only
   * one of readCh or writeCh should be non-null.
   * 
   * @see #channelRead(ReadableByteChannel, ByteBuffer)
   * @see #channelWrite(WritableByteChannel, ByteBuffer)
   */
  private static int channelIO(ReadableByteChannel readCh, 
                               WritableByteChannel writeCh,
                               ByteBuffer buf) throws IOException {
    
    int originalLimit = buf.limit();
    int initialRemaining = buf.remaining();
    int ret = 0;
    
    while (buf.remaining() > 0) {
      try {
        int ioSize = Math.min(buf.remaining(), NIO_BUFFER_LIMIT);
        buf.limit(buf.position() + ioSize);
        
        ret = (readCh == null) ? writeCh.write(buf) : readCh.read(buf); 
        
        if (ret < ioSize) {
          break;
        }

      } finally {
        buf.limit(originalLimit);        
      }
    }

    int nBytes = initialRemaining - buf.remaining(); 
    return (nBytes > 0) ? nBytes : ret;
  }      
}
