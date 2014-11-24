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

import java.lang.reflect.Proxy;
import java.lang.reflect.Method;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.io.*;
import java.util.Map;
import java.util.HashMap;

import javax.net.SocketFactory;

import org.apache.commons.logging.*;

import org.apache.hadoop.io.*;
import org.apache.hadoop.security.SaslRpcServer;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.SecretManager;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.hadoop.conf.*;

import org.apache.hadoop.net.NetUtils;

/** A simple RPC mechanism.
 * 用org.apache.hadoop.ipc包实现的RPC机制.
 * A <i>protocol</i> is a Java interface.  All parameters and return types must
 * be one of:
 *java的primitive type比如int,double,byte,char,short等
 * <ul> <li>a primitive type, <code>boolean</code>, <code>byte</code>,
 * <code>char</code>, <code>short</code>, <code>int</code>, <code>long</code>,
 * <code>float</code>, <code>double</code>, or <code>void</code>; or</li>
 *
 * <li>a {@link String}; or</li>
 *
 * <li>a {@link Writable}; or</li>
 *
 * <li>an array of the above types</li> </ul>
 *
 * All methods in the protocol should throw only IOException.  No field data of
 * the protocol instance is transmitted.
 */
public class RPC {
  private static final Log LOG =
    LogFactory.getLog(RPC.class);
  /**RPC类没有public属性的构造函数*/
  private RPC() {}                                  // no public ctor
  /**********************************************1.Invocation静态类*************************************************************************/
  /** 一个方法调用类,包括方法名和参数.
   *  A method invocation, including the method name and its parameters.*/
  private static class Invocation implements Writable, Configurable {
    private String methodName;
    private Class[] parameterClasses;
    private Object[] parameters;
    private Configuration conf;

    public Invocation() {}

    public Invocation(Method method, Object[] parameters) {
      this.methodName = method.getName();
      this.parameterClasses = method.getParameterTypes();
      this.parameters = parameters;
    }

    /** The name of the method invoked. */
    public String getMethodName() { return methodName; }

    /** The parameter classes. */
    public Class[] getParameterClasses() { return parameterClasses; }

    /** The parameter instances. */
    public Object[] getParameters() { return parameters; }
    /**从DataInput 流读取方法名,参数名，参数类*/
    public void readFields(DataInput in) throws IOException {
      methodName = UTF8.readString(in);
      parameters = new Object[in.readInt()];
      parameterClasses = new Class[parameters.length];
      ObjectWritable objectWritable = new ObjectWritable();
      for (int i = 0; i < parameters.length; i++) {
        parameters[i] = ObjectWritable.readObject(in, objectWritable, this.conf);
        parameterClasses[i] = objectWritable.getDeclaredClass();
      }
    }
    /**将方法名，参数名，参数类写到DataOutput 流*/
    public void write(DataOutput out) throws IOException {
      UTF8.writeString(out, methodName);
      out.writeInt(parameterClasses.length);
      for (int i = 0; i < parameterClasses.length; i++) {
        ObjectWritable.writeObject(out, parameters[i], parameterClasses[i],
                                   conf);
      }
    }
    /**toString()方法，返回方法名和参数组成的字符串*/
    public String toString() {
      StringBuffer buffer = new StringBuffer();
      buffer.append(methodName);
      buffer.append("(");
      for (int i = 0; i < parameters.length; i++) {
        if (i != 0)
          buffer.append(", ");
        buffer.append(parameters[i]);
      }
      buffer.append(")");
      return buffer.toString();
    }
    /**设置conf*/
    public void setConf(Configuration conf) {
      this.conf = conf;
    }
    
    public Configuration getConf() {
      return this.conf;
    }

  }/*****************************************************************************END OF Invocation**************************************************/
  /**********************************************2.ClientCache静态类，根据socket factory作为hash key以缓存RPC client************************************/
  /** Cache a client using its socket factory as the hash key */
  static private class ClientCache {
    private Map<SocketFactory, Client> clients =
      new HashMap<SocketFactory, Client>();

    /**根据conf和给定的socket factory从缓存获取对应的client.该方法为
     * Construct & cache an IPC client with the user-provided SocketFactory 
     * if no cached client exists.
     * 
     * @param conf Configuration
     * @return an IPC client
     */
    private synchronized Client getClient(Configuration conf,
        SocketFactory factory) {
      // Construct & cache client.  The configuration is only used for timeout,
      // and Clients have connection pools.  So we can either (a) lose some
      // connection pooling and leak sockets, or (b) use the same timeout for all
      // configurations.  Since the IPC is usually intended globally, not
      // per-job, we choose (a).
      Client client = clients.get(factory);
      if (client == null) {
        client = new Client(ObjectWritable.class, conf, factory);
        clients.put(factory, client);
      } else {
        client.incCount();
      }
      return client;
    }

    /**根据conf获取默认缓存的client,如果无缓存，则根据conf和默认的socketFactory创建Client
     * Construct & cache an IPC client with the default SocketFactory 
     * if no cached client exists.
     * 
     * @param conf Configuration
     * @return an IPC client
     */
    private synchronized Client getClient(Configuration conf) {
      return getClient(conf, SocketFactory.getDefault());
    }

    /**Stop一个RPC client. 只有这个RPC client的引用变为0时才执行该操作.
     * Stop a RPC client connection 
     * A RPC client is closed only when its reference count becomes zero.
     */
    private void stopClient(Client client) {
      synchronized (this) {
        client.decCount();
        if (client.isZeroReference()) {//client的引用为0，从缓存(hashmap)里删除该client；hashmap非线程安全，故对this加锁.
          clients.remove(client.getSocketFactory());
        }
      }
      if (client.isZeroReference()) {//client的引用为0，执行client.stop().
        client.stop();
      }
    }
  }/*****************************************************************************END OF CLIENT CACHE******************************************************/
  // ClientCache,缓存所有的rpc client
  private static ClientCache CLIENTS=new ClientCache();
  //for unit testing only
  static Client getClient(Configuration conf) {
    return CLIENTS.getClient(conf);
  }/*****************************************************************************************************************************************************
   ****3.Invoker，是一个InvocationHadler类,定义了调用发起者的id,及其invoker如何实现；RPC.getProxy方法中该类的实例作为生成动态代理时的invocationHandler参数****/
  /**org.apache.hadoop.ipc.RPC.Invoker,实现了java.lang.reflect.InvocationHandler接口*/
  private static class Invoker extends HadoopInvoker {
    private Client.ConnectionId remoteId;//client到servers的connection由remoteAddress,protocol,ticket唯一标识
    /**Invoker构造函数,根据protocol,address,ticket初始化remoteId,并从CLIENTS缓存获取client*/
    public Invoker(Class<? extends VersionedProtocol> protocol,
        InetSocketAddress address, UserGroupInformation ticket,
        Configuration conf, SocketFactory factory,
        int rpcTimeout) throws IOException {
      this.remoteId = Client.ConnectionId.getConnectionId(address, protocol,
          ticket, rpcTimeout, conf);
      this.client = CLIENTS.getClient(conf, factory);
    }
    /**实现InvocationHander接口定义的invoker方法:通过client.call()向RPC server发起一次RPC请求实现方法调用.*/
    public Object invoke(Object proxy, Method method, Object[] args)
      throws Throwable {
      final boolean logDebug = LOG.isDebugEnabled();
      long startTime = 0;
      if (logDebug) {
        startTime = System.currentTimeMillis();
      }
      //这里是重点，Invoker作为InvocationHandler类，它的invoker方法定义了如何对代理对象的方法调用：通过client向server发起rpc call实现.
      ObjectWritable value = (ObjectWritable)
        client.call(new Invocation(method, args), remoteId);
      if (logDebug) {
        long callTime = System.currentTimeMillis() - startTime;
        LOG.debug("Call: " + method.getName() + " " + callTime);
      }
      return value.get();
    }
    
  }/**@NOTE jicheng.song hadoop-1.2里有这个path,对Invoker类添加一个close方法，RPC.stopProxy()方法会调用colse()，关闭负责本次调用的RPC client 
   @ADDCODE xianquan.zhang*/
  private static abstract class HadoopInvoker implements InvocationHandler{
	  protected boolean isClosed = false;
	  protected Client client;
	  /* close the IPC client that's responsible for this invoker's RPCs */ 
	    synchronized protected void close() {
	      if (!isClosed) {
	        isClosed = true;
	        CLIENTS.stopClient(client);
	      }
	    }
  }
  /**@deprecated @NOTE jicheng.song, 4.RPCRetryAndSwitchInvoker是为了实现JobTracker HA设计的，目前jobtracker HA已下线*****************************************
   *@ADDCODE xianquan.zhang */
  private static class RPCRetryAndSwitchInvoker extends HadoopInvoker{
    private Client.ConnectionId[] remoteIdArray=null;
    private Client.ConnectionId remoteId;
    
    public RPCRetryAndSwitchInvoker(Class<? extends VersionedProtocol> protocol,
	        InetSocketAddress address, UserGroupInformation ticket,
	        Configuration conf, SocketFactory factory,
	        int rpcTimeout) throws IOException {
      String jtServers=conf.get("jobtracker.servers","");
      String[] addresses=jtServers.split(",");
      int len=addresses.length;
      remoteIdArray=new Client.ConnectionId[len];
      for(int i=0;i<len;i++){
    	remoteIdArray[i]=Client.ConnectionId.getConnectionId(NetUtils.createSocketAddr(addresses[i]), protocol,
		          ticket, rpcTimeout, conf);
      }
      this.remoteId=remoteIdArray[0];
	  this.client = CLIENTS.getClient(conf, factory);
    }
	    
	@Override
	public Object invoke(Object proxy, Method method, Object[] args)
			throws Throwable {
	 final boolean logDebug = LOG.isDebugEnabled();
      long startTime = 0;
      if (logDebug) {
        startTime = System.currentTimeMillis();
      }

      ObjectWritable value = null;
      Exception failException=null;
      try{
    	  value=(ObjectWritable)
          client.call(new Invocation(method, args), remoteId);
      }catch(Exception e){
    	  LOG.warn("Fail to call job tracker:"+remoteId.getAddress().toString()+",and trg again.The exception is : "+e.getMessage());
    	  for(int i=0;i<remoteIdArray.length;i++){
    		  try{
    			  if(remoteIdArray[i]==this.remoteId){
    				  continue;
    			  }
    			  this.remoteId =remoteIdArray[i];
        		  value=(ObjectWritable)
        		          client.call(new Invocation(method, args), remoteId);
        		  LOG.info("Success to reconnection address:"+remoteId.getAddress().toString());
        		  break;
    		  }catch(Exception e1){
    			  failException=e1;
    			  LOG.warn("Fail to reconnection address:"+remoteId.getAddress().toString()+",and try again.Fail Exception is "+e1.getMessage());
    			  continue;
    		  }
    		  
    	  }
      }
      if (logDebug) {
        long callTime = System.currentTimeMillis() - startTime;
        LOG.debug("Call: " + method.getName() + " " + callTime);
      }
      if(value==null){
    	  throw failException;
      }
      return value.get();
	}
	
    }
  /** RPC 协议版本不一致异常
   * A version mismatch for the RPC protocol.
   */
  public static class VersionMismatch extends IOException {
    private String interfaceName;
    private long clientVersion;
    private long serverVersion;
    
    /**
     * Create a version mismatch exception
     * @param interfaceName the name of the protocol mismatch
     * @param clientVersion the client's version of the protocol
     * @param serverVersion the server's version of the protocol
     */
    public VersionMismatch(String interfaceName, long clientVersion,
                           long serverVersion) {
      super("Protocol " + interfaceName + " version mismatch. (client = " +
            clientVersion + ", server = " + serverVersion + ")");
      this.interfaceName = interfaceName;
      this.clientVersion = clientVersion;
      this.serverVersion = serverVersion;
    }
    
    /**
     * Get the interface name
     * @return the java class name 
     *          (eg. org.apache.hadoop.mapred.InterTrackerProtocol)
     */
    public String getInterfaceName() {
      return interfaceName;
    }
    
    /**
     * Get the client's preferred version
     */
    public long getClientVersion() {
      return clientVersion;
    }
    
    /**
     * Get the server's agreed to version.
     */
    public long getServerVersion() {
      return serverVersion;
    }
  }
  //////////////////////////BEGIN OF getProxy :获取远程服务器的连接代理///////////////////////////////////////////////////////////////////////////////////////
  public static VersionedProtocol waitForProxy(
      Class<? extends VersionedProtocol> protocol,
      long clientVersion,
      InetSocketAddress addr,
      Configuration conf
      ) throws IOException {
    return waitForProxy(protocol, clientVersion, addr, conf, 0, Long.MAX_VALUE);
  }

  /**获取远程服务器的连接代理:
   * Get a proxy connection to a remote server
   * @param protocol protocol class,协议类
   * @param clientVersion client version,client版本
   * @param addr remote address,远程服务器地址
   * @param conf configuration to use，配置
   * @param connTimeout time in milliseconds before giving up，超时时间
   * @return the proxy,返回远程服务器连接代理
   * @throws IOException if the far end through a RemoteException
   */
  static VersionedProtocol waitForProxy(
                      Class<? extends VersionedProtocol> protocol,
                                               long clientVersion,
                                               InetSocketAddress addr,
                                               Configuration conf,
                                               long connTimeout)
                                               throws IOException { 
    return waitForProxy(protocol, clientVersion, addr, conf, 0, connTimeout);
  }

  static VersionedProtocol waitForProxy(
                      Class<? extends VersionedProtocol> protocol,
                                               long clientVersion,
                                               InetSocketAddress addr,
                                               Configuration conf,
                                               int rpcTimeout,
                                               long connTimeout)
                                               throws IOException { 
    long startTime = System.currentTimeMillis();
    IOException ioe;
    while (true) {
      try {
        return getProxy(protocol, clientVersion, addr, conf, rpcTimeout);
      } catch(ConnectException se) {  // namenode has not been started
        LOG.info("Server at " + addr + " not available yet, Zzzzz...");
        ioe = se;
      } catch(SocketTimeoutException te) {  // namenode is busy
        LOG.info("Problem connecting to server: " + addr);
        ioe = te;
      }
      // check if timed out
      if (System.currentTimeMillis()-connTimeout >= startTime) {
        throw ioe;
      }

      // wait for retry
      try {
        Thread.sleep(1000);
      } catch (InterruptedException ie) {
        // IGNORE
      }
    }
  }

  /** Construct a client-side proxy object that implements the named protocol,
   * talking to a server at the named address. */
  public static VersionedProtocol getProxy(
      Class<? extends VersionedProtocol> protocol,
      long clientVersion, InetSocketAddress addr, Configuration conf,
      SocketFactory factory) throws IOException {
    UserGroupInformation ugi = UserGroupInformation.getCurrentUser();
    return getProxy(protocol, clientVersion, addr, ugi, conf, factory, 0);
  }
 
  /** Construct a client-side proxy object that implements the named protocol,
   * talking to a server at the named address. */
  public static VersionedProtocol getProxy(
      Class<? extends VersionedProtocol> protocol,
      long clientVersion, InetSocketAddress addr, Configuration conf,
      SocketFactory factory, int rpcTimeout) throws IOException {
    UserGroupInformation ugi = UserGroupInformation.getCurrentUser();
    return getProxy(protocol, clientVersion, addr, ugi, conf, factory, rpcTimeout);
  }
    
  /** Construct a client-side proxy object that implements the named protocol,
   * talking to a server at the named address. */
  public static VersionedProtocol getProxy(
      Class<? extends VersionedProtocol> protocol,
      long clientVersion, InetSocketAddress addr, UserGroupInformation ticket,
      Configuration conf, SocketFactory factory) throws IOException {
    return getProxy(protocol, clientVersion, addr, ticket, conf, factory, 0);
  }/*********************************************************************RPC.waitForProxy()方法最终通过getProxy方法实现*******************/
  /** 构建一个client-site 代理对象，该对象实现了给定的protocol接口.
   * Construct a client-side proxy object that implements the named protocol,
   * talking to a server at the named address. */
  public static VersionedProtocol getProxy(
      Class<? extends VersionedProtocol> protocol,
      long clientVersion, InetSocketAddress addr, UserGroupInformation ticket,
      Configuration conf, SocketFactory factory, int rpcTimeout) throws IOException {
	/**是否启用了kerberos认证*/
    if (UserGroupInformation.isSecurityEnabled()) {
      SaslRpcServer.init(conf);
    }
    /**@CHANGECODE xianquan.zhang 2012-8-17*/
    VersionedProtocol proxy =null;
    /**@ADDCOMMENT xianquan.zhang 判断是否为Jobtracker HA 代理实例*/
    String strAddr=addr.getHostName()+":"+addr.getPort();
    String jtServers=conf.get("jobtracker.servers","");
    if(jtServers.contains(strAddr)){//(1).1启用了jobtracker HA
    	proxy =(VersionedProtocol) Proxy.newProxyInstance(
	            protocol.getClassLoader(), new Class[] { protocol },
	            new RPCRetryAndSwitchInvoker(protocol, addr, ticket, conf, factory, rpcTimeout));
    }else{//(1).2未启用jobtracker HA, 采用java.lang.reflect.Proxy.newProxyInstance()方法生成代理.
    	proxy =(VersionedProtocol) Proxy.newProxyInstance(						//这里采用的是动态代理技术,proxy.newProxyInstance根据classLoader、class[]{protocol}、invoker生成代理proxy
	            protocol.getClassLoader(), new Class[] { protocol },			//我们为InvokerHandler(也即Invoker)的构造器传递了protocol等参数,invokerHandler拦截proxy的方法调用后，会调用
	            new Invoker(protocol, addr, ticket, conf, factory, rpcTimeout));//invoke(Object proxy=null, Method method, Object[] args)方法响应调用者对proxy的方法调用. 我们可以根据protocol
    }																			//构建一个instance让其执行方法调用，也可以将这个方法调用发送给远程protocol实例(ipc server)去执行。显然RPC采用的是后者.							
    //(2)生成代理后，首先要判断client和server的协议版本是否一致.//proxy.getProtocolVersion()将发起一次RPC请求,获取server版本号
    long serverVersion = proxy.getProtocolVersion(protocol.getName(), 
                                                  clientVersion);
    if (serverVersion == clientVersion) {
      return proxy;
    } else {
      throw new VersionMismatch(protocol.getName(), clientVersion, 
                                serverVersion);
    }
  }

  /**
   * Construct a client-side proxy object with the default SocketFactory
   * 
   * @param protocol
   * @param clientVersion
   * @param addr
   * @param conf
   * @return a proxy instance
   * @throws IOException
   */
  public static VersionedProtocol getProxy(
      Class<? extends VersionedProtocol> protocol,
      long clientVersion, InetSocketAddress addr, Configuration conf)
      throws IOException {
    return getProxy(protocol, clientVersion, addr, conf,
        NetUtils.getDefaultSocketFactory(conf), 0);
  }

  public static VersionedProtocol getProxy(
      Class<? extends VersionedProtocol> protocol,
      long clientVersion, InetSocketAddress addr, Configuration conf, int rpcTimeout)
      throws IOException {

    return getProxy(protocol, clientVersion, addr, conf,
        NetUtils.getDefaultSocketFactory(conf), rpcTimeout);
  }

  /**
   * Stop this proxy and release its invoker's resource
   * @param proxy the proxy to be stopped
   */
  public static void stopProxy(VersionedProtocol proxy) {
    if (proxy!=null) {
      ((HadoopInvoker)Proxy.getInvocationHandler(proxy)).close();
    }
  }//////////////////////////////END OF getProxy ///////////////////////////////////////////////////////////////////////////////////////////////////////
   //RPC.call(method,params,addrs,ticket,conf). 
  /** 
   * Expert: Make multiple, parallel calls to a set of servers.
   * @deprecated Use {@link #call(Method, Object[][], InetSocketAddress[], UserGroupInformation, Configuration)} instead 
   */
  public static Object[] call(Method method, Object[][] params,
                              InetSocketAddress[] addrs, Configuration conf)
    throws IOException, InterruptedException {
    return call(method, params, addrs, null, conf);
  }
  /**对一组服务器并行发起rpc call调用
   * Expert: Make multiple, parallel calls to a set of servers. */
  public static Object[] call(Method method, Object[][] params,
                              InetSocketAddress[] addrs, 
                              UserGroupInformation ticket, Configuration conf)
    throws IOException, InterruptedException {

    Invocation[] invocations = new Invocation[params.length];
    for (int i = 0; i < params.length; i++)
      invocations[i] = new Invocation(method, params[i]);
    Client client = CLIENTS.getClient(conf);
    try {
    Writable[] wrappedValues = 
      client.call(invocations, addrs, method.getDeclaringClass(), ticket, conf);
    
    if (method.getReturnType() == Void.TYPE) {
      return null;
    }

    Object[] values =
      (Object[])Array.newInstance(method.getReturnType(), wrappedValues.length);
    for (int i = 0; i < values.length; i++)
      if (wrappedValues[i] != null)
        values[i] = ((ObjectWritable)wrappedValues[i]).get();
    
    return values;
    } finally {
      CLIENTS.stopClient(client);
    }
  }
///////////////////////////////////////BEGIN of getServer(),构造一个实现protocol接口的RPC server实例，侦听给定的address:port///////////////////////////////
  /** Construct a server for a protocol implementation instance listening on a
   * port and address. */
  public static Server getServer(final Object instance, final String bindAddress, final int port, Configuration conf) 
    throws IOException {
    return getServer(instance, bindAddress, port, 1, false, conf);
  }

  /** Construct a server for a protocol implementation instance listening on a
   * port and address. */
  public static Server getServer(final Object instance, final String bindAddress, final int port,
                                 final int numHandlers,
                                 final boolean verbose, Configuration conf) 
    throws IOException {
    return getServer(instance, bindAddress, port, numHandlers, verbose, conf, null);
  }
  /** 构建一个RPC Ｓerver. Namenode Datanode Jobtracker Tasktracker服务进程都是通过该方法创建的.
   * Construct a server for a protocol implementation instance listening on a
   * port and address, with a secret manager. */
  public static Server getServer(final Object instance, final String bindAddress, final int port,
                                 final int numHandlers,
                                 final boolean verbose, Configuration conf,
                                 SecretManager<? extends TokenIdentifier> secretManager) 
    throws IOException {
    return new Server(instance, conf, bindAddress, port, numHandlers, verbose, secretManager);
  }///////////////////////////////////////END of getServer(),///////////////////////////////
  /******************************5.定义RPC Server类***************************************************************************************************/
  /** An RPC Server. */
  public static class Server extends org.apache.hadoop.ipc.Server {
    private Object instance;//对外提供服务的对象.
    private boolean verbose;//verbose为true时打详细log

    /** Construct an RPC server.
     * @param instance the instance whose methods will be called
     * @param conf the configuration to use
     * @param bindAddress the address to bind on to listen for connection
     * @param port the port to listen for connections on
     */
    public Server(Object instance, Configuration conf, String bindAddress, int port) 
      throws IOException {
      this(instance, conf,  bindAddress, port, 1, false, null);
    }
    
    private static String classNameBase(String className) {
      String[] names = className.split("\\.", -1);
      if (names == null || names.length == 0) {
        return className;
      }
      return names[names.length-1];
    }
    
    /** Construct an RPC server.
     * @param instance the instance whose methods will be called
     * @param conf the configuration to use
     * @param bindAddress the address to bind on to listen for connection
     * @param port the port to listen for connections on
     * @param numHandlers the number of method handler threads to run
     * @param verbose whether each call should be logged
     */
    public Server(Object instance, Configuration conf, String bindAddress,  int port,
                  int numHandlers, boolean verbose, 
                  SecretManager<? extends TokenIdentifier> secretManager) 
        throws IOException {
      super(bindAddress, port, Invocation.class, numHandlers, conf,
          classNameBase(instance.getClass().getName()), secretManager);
      this.instance = instance;
      this.verbose = verbose;
    }
    /**实现org.apache.hadoop.ipc.Server抽像类的call方法,其中param是一个Invocation对象*/
    public Writable call(Class<?> protocol, Writable param, long receivedTime) 
    throws IOException {
      try {
        Invocation call = (Invocation)param;
        if (verbose) log("Call: " + call);

        Method method =
          protocol.getMethod(call.getMethodName(),
                                   call.getParameterClasses());
        method.setAccessible(true);

        long startTime = System.currentTimeMillis();
        Object value = method.invoke(instance, call.getParameters());
        int processingTime = (int) (System.currentTimeMillis() - startTime);
        int qTime = (int) (startTime-receivedTime);
        if (LOG.isDebugEnabled()) {
          LOG.debug("Served: " + call.getMethodName() +
                    " queueTime= " + qTime +
                    " procesingTime= " + processingTime);
        }
        rpcMetrics.addRpcQueueTime(qTime);
        rpcMetrics.addRpcProcessingTime(processingTime);
        rpcMetrics.addRpcProcessingTime(call.getMethodName(), processingTime);
        if (verbose) log("Return: "+value);

        return new ObjectWritable(method.getReturnType(), value);

      } catch (InvocationTargetException e) {
        Throwable target = e.getTargetException();
        if (target instanceof IOException) {
          throw (IOException)target;
        } else {
          IOException ioe = new IOException(target.toString());
          ioe.setStackTrace(target.getStackTrace());
          throw ioe;
        }
      } catch (Throwable e) {
        if (!(e instanceof IOException)) {
          LOG.error("Unexpected throwable object ", e);
        }
        IOException ioe = new IOException(e.toString());
        ioe.setStackTrace(e.getStackTrace());
        throw ioe;
      }
    }
  }

  private static void log(String value) {
    if (value!= null && value.length() > 55)
      value = value.substring(0, 55)+"...";
    LOG.info(value);
  }
}
