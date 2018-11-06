// Copyright (c) 2011, Lars-Ake Fredlund
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//     // Redistributions of source code must retain the above copyright
//       notice, this list of conditions and the following disclaimer.
//     // Redistributions in binary form must reproduce the above copyright
//       notice, this list of conditions and the following disclaimer in the
//       documentation and/or other materials provided with the distribution.
//     // Neither the name of the copyright holders nor the
//       names of its contributors may be used to endorse or promote products
//       derived from this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS ''AS IS''
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
// ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
// CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
// SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
// BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
// WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
// OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
// ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

// @author Lars-Ake Fredlund (lfredlund@fi.upm.es)
// @copyright 2011 Lars-Ake Fredlund
//

package javaErlang;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.logging.ConsoleHandler;

import javassist.util.proxy.ProxyFactory;

import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangBoolean;
import com.ericsson.otp.erlang.OtpErlangByte;
import com.ericsson.otp.erlang.OtpErlangChar;
import com.ericsson.otp.erlang.OtpErlangDouble;
import com.ericsson.otp.erlang.OtpErlangFloat;
import com.ericsson.otp.erlang.OtpErlangInt;
import com.ericsson.otp.erlang.OtpErlangList;
import com.ericsson.otp.erlang.OtpErlangLong;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangPid;
import com.ericsson.otp.erlang.OtpErlangShort;
import com.ericsson.otp.erlang.OtpErlangString;
import com.ericsson.otp.erlang.OtpErlangTuple;
import com.ericsson.otp.erlang.OtpMbox;
import com.ericsson.otp.erlang.OtpNode;

@SuppressWarnings("rawtypes")
public class JavaErlang {
    volatile private Map<RefEqualsObject, JavaObjectEntry> toErlangMap;
    volatile private Map<JavaObjectKey, JavaObjectEntry> fromErlangMap;
    volatile private Map<Object, OtpErlangObject> accToErlangMap;
    volatile private Map<OtpErlangObject, Object> accFromErlangMap;
    volatile private Map<OtpErlangObject, ThreadMsgHandler> threadMap;
    volatile private Map<Class,Integer> classMap;
    volatile int objCounter = 0;
    volatile int classCounter = 0;
    volatile private int accCounter = 0;
    volatile private int threadCounter = 0;
    volatile String  connectedErlangNode = null;
    volatile OtpMbox msgs = null;
    volatile OtpErlangObject nodeIdentifier = null;
    volatile boolean returnOtpErlangObject = false;
    volatile OtpNode node = null;
    static volatile Logger logger = Logger.getLogger("JavaErlangLogger");
    static volatile JavaErlang javaErlang;

    boolean isConnected = false;

    public static void main(final String args[]) {
	Level logLevel = Level.WARNING;
	int currentArg = 0;
        final String name = args[currentArg++];
	String cookie = null;
        boolean returnOtpErlangObject = false;

	while (currentArg < args.length) {
	    final String arg = args[currentArg++];

	    if (arg.equals("-loglevel"))
		if (currentArg < args.length)
		    logLevel = Level.parse(args[currentArg++]);
		else {
		    System.err.println("Missing argument for -loglevel option");
		    System.exit(-1);
		}
	    else if (arg.equals("-setcookie"))
		if (currentArg < args.length)
		    cookie = args[currentArg++];
		else {
		    System.err.println("Missing argument for -setcookie option");
		    System.exit(-1);
		}
	    else if (arg.equals("-returnOtpErlangObject")) {
              returnOtpErlangObject = true;
              ++currentArg;
            }
	    else {
                System.err.println("\rCannot understand argument " + arg);
                System.exit(-1);
	    }
	}

        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setLevel(logLevel);
        logger.addHandler(consoleHandler);

        try {
	  receiveConnection(logLevel,name,cookie,returnOtpErlangObject);
	  System.exit(0);
        } catch (final Exception e) {
            logger.log
                (Level.SEVERE,
                 "*** Unexpected exception failure in JavaErlang: "
                 + e,e);
        }

    }

  public static void receiveConnection(Level logLevel, String name, String cookie, boolean returnOtpErlangObject) throws Exception {
    new JavaErlang(logLevel,name,cookie,returnOtpErlangObject).do_receive();
  }

  public static void reportAndReceiveConnection(Level logLevel, String ourName, String otherNode, String reportName, String cookie, boolean returnOtpErlangObject) throws Exception {
    
    new JavaErlang(logLevel,ourName,cookie,returnOtpErlangObject).do_connect(ourName, otherNode, reportName);
  }

  public JavaErlang(final Level logLevel, final String name, final String cookie, boolean returnOtpErlangObject) {
        toErlangMap = new ConcurrentHashMap<RefEqualsObject, JavaObjectEntry>();
        fromErlangMap = new ConcurrentHashMap<JavaObjectKey, JavaObjectEntry>();
        accToErlangMap = new ConcurrentHashMap<Object, OtpErlangObject>();
        accFromErlangMap = new ConcurrentHashMap<OtpErlangObject, Object>();
        classMap = new ConcurrentHashMap<Class, Integer>();
        threadMap = new ConcurrentHashMap<OtpErlangObject, ThreadMsgHandler>();

        this.returnOtpErlangObject = returnOtpErlangObject;

	logger.setLevel(logLevel);

        try {
	    if (cookie == null) node = new OtpNode(name);
	    else node = new OtpNode(name,cookie);

            node.registerStatusHandler(new OtpStatusHandler(nodeIdentifier));
            if (logger.isLoggable(Level.INFO)) {
                logger.log(Level.INFO,"\rRegistered host " + node.node());
            }
            msgs = node.createMbox("javaNode");
	    javaErlang = this;
        } catch (final Throwable e) {
            if (logger.isLoggable(Level.SEVERE))
                logger.log
                    (Level.SEVERE,
                     "*** Unexpected exception failure in JavaErlang: "
                     + e,
                     e);
        }
    }

  void do_connect(String ourNode, String otherNode, String reportName) throws Exception {
      if (!node.ping(otherNode,3000)) {
	if (logger.isLoggable(Level.FINER))
	  logger.log(Level.SEVERE,"\nCould not connect to host "+otherNode);
      } else {
	msgs.send(reportName, otherNode, new OtpErlangString(ourNode));
	do_receive();
      }
    }

    void do_receive() throws Exception {
      boolean continuing = true;
        do {
	  if (logger.isLoggable(Level.FINER))
	    logger.log(Level.FINER,"wating to receive a message on "+msgs.getName());
            final OtpErlangObject msg = msgs.receive();
            if (logger.isLoggable(Level.FINER)) {
                logger.log(Level.FINER,"\rGot message " + msg);
            }

            if (msg instanceof OtpErlangTuple) {
                final OtpErlangTuple t = (OtpErlangTuple) msg;
                if (t.arity() == 3 && t.elementAt(0) instanceof OtpErlangLong
                    && t.elementAt(2) instanceof OtpErlangPid) {
                    continuing = handle_nonthread_msg(t);
                } else if (t.arity() == 4
                           && t.elementAt(0) instanceof OtpErlangLong
                           && t.elementAt(3) instanceof OtpErlangPid) {
                    handle_thread_msg(t);
                } else if (t.arity() == 2
                           && t.elementAt(0) instanceof OtpErlangLong) {
                    handle_noncall_msg(t);
                } else {
		    if (logger.isLoggable(Level.FINER))
			logger.log(Level.FINER,"\nMalformed message " + msg
				   + " received");
                }
            } else {
		if (logger.isLoggable(Level.FINER))
		    logger.log(Level.FINER,"\nMalformed message " + msg + " received");
            }
        } while (continuing);
    }

    public void reply(final Object reply, final OtpErlangPid replyPid)
        throws Exception {
        // logger.log(Level.FINER,"returning "+return_value(reply)+" to "+replyPid);
        msgs.send(replyPid, return_value(reply));
    }

    boolean handle_nonthread_msg(final OtpErlangTuple t) throws Exception {
        final short tag = ((OtpErlangLong) t.elementAt(0)).uShortValue();
        final OtpErlangObject argument = t.elementAt(1);
        final OtpErlangPid replyPid = (OtpErlangPid) t.elementAt(2);
        if (!isConnected) {
            if (tag == Tags.connectTag) {
	      if (logger.isLoggable(Level.FINER))
		logger.log
		  (Level.FINE,"\nJava got Erlang connect with node id " +
		   argument);

                final String nameOfRunningVM = ManagementFactory
                    .getRuntimeMXBean().getName();
                final int p = nameOfRunningVM.indexOf('@');
                final String pid = nameOfRunningVM.substring(0, p);
                final Integer intPid = Integer.valueOf(pid);
                if (nodeIdentifier == null) {
                    nodeIdentifier = argument;
                }
                reply(makeErlangTuple(new OtpErlangAtom("connected"),
				      argument,
                                      msgs.self(), new OtpErlangLong(intPid)), replyPid);
		connectedErlangNode = replyPid.node();
                isConnected = true;
            } else {
		if (logger.isLoggable(Level.FINER))
		    logger.log(Level.FINER,"\nFirst message should be connect " + t);
            }
        } else {
	  if (tag == Tags.terminateTag) {
            if (logger.isLoggable(Level.FINER)) {
                logger.log(Level.FINER,"\r\nterminating java...");
            }
	    reply(new OtpErlangAtom("ok"), replyPid);
	    return false;
	  } else {
	    Object result;
	    try {
	      result = handleNonThreadMsg(tag, argument, replyPid);
	    } catch (final Throwable e) {
	      if (logger.isLoggable(Level.WARNING)) {
		logger.log(Level.WARNING,"\r\n*** Exception " + e + " thrown\r");
		e.printStackTrace();
	      }
	      result = e;
	    }
            reply(result, replyPid);
	  }
        }
	return true;
    }

    void handle_noncall_msg(final OtpErlangTuple t) throws Exception {
        final short tag = ((OtpErlangLong) t.elementAt(0)).uShortValue();
        final OtpErlangObject argument = t.elementAt(1);
        try {
            handleNonCallMsg(tag, argument);
        } catch (final Throwable e) {
            if (logger.isLoggable(Level.WARNING)) {
                logger.log(Level.WARNING,"\r\n*** Exception " + e + " thrown\r");
                e.printStackTrace();
            }
        }
    }

    void handle_thread_msg(final OtpErlangTuple t) throws Exception {
        final Object map_result = threadMap.get(t.elementAt(1));
        if (map_result instanceof ThreadMsgHandler) {
            final ThreadMsgHandler th = (ThreadMsgHandler) map_result;
            th.queue.put(t);
        } else {
	    if (logger.isLoggable(Level.FINER))
		logger.log(Level.FINER,"Thread " + t.elementAt(1) + " not found");
        }
    }

    OtpErlangObject handleNonThreadMsg(final short tag,
                                       final OtpErlangObject argument, final OtpErlangPid replyPid)
        throws Exception {
        switch (tag) {
        case Tags.resetTag:
            objCounter = 0;
            toErlangMap = new ConcurrentHashMap<RefEqualsObject, JavaObjectEntry>();
            fromErlangMap = new ConcurrentHashMap<JavaObjectKey, JavaObjectEntry>();
            for (final ThreadMsgHandler th : threadMap.values()) {
                stop_thread(th);
            }
            threadMap = new ConcurrentHashMap<OtpErlangObject, ThreadMsgHandler>();
            System.gc();
            return new OtpErlangAtom("ok");
        case Tags.connectTag:
            return new OtpErlangAtom("already_connected");
        case Tags.lookupClassTag:
            return lookupClass(argument);
        case Tags.getConstructorsTag:
            return getConstructors(argument);
        case Tags.getMethodsTag:
            return getMethods(argument);
        case Tags.getClassesTag:
            return getClasses(argument);
        case Tags.getFieldsTag:
            return getFields(argument);
        case Tags.getConstructorTag:
            return getConstructor(argument);
        case Tags.getMethodTag:
            return getMethod(argument);
        case Tags.getFieldTag:
            return getField(argument);
        case Tags.objTypeCompatTag:
            return objTypeCompat(argument);
        case Tags.freeTag:
            return free(argument);
        case Tags.freeInstanceTag:
            return freeInstance(argument);
        case Tags.memoryUsageTag:
            return memoryUsage(argument);
        case Tags.identityTag:
            return identity(argument);
        case Tags.createThreadTag:
            return create_thread();
        case Tags.new_proxy_classTag:
            return new_proxy_class(argument);
        case Tags.new_proxy_objectTag:
            return new_proxy_object(argument);
        case Tags.proxy_replyTag:
            return proxy_reply(argument);
        default:
            logger.log
                (Level.SEVERE,
                 "*** Error: JavaErlang: \nTag " + tag
                 + " not recognized");
            throw new Exception();
        }
    }

    OtpErlangObject handleNonCallMsg(final short tag,
                                     final OtpErlangObject argument)
        throws Exception {
        switch (tag) {
        case Tags.freeInstanceTag:
            return freeInstance(argument);
        case Tags.stopThreadTag:
            final Object map_result = threadMap.get(argument);
            if (map_result instanceof ThreadMsgHandler) {
                final ThreadMsgHandler th = (ThreadMsgHandler) map_result;
                threadMap.remove(argument);
                stop_thread(th);
            } else {
		if (logger.isLoggable(Level.FINER))
		    logger.log
			(Level.FINER,
			 "*** Warning: thread missing in stopThread");
                //throw new Exception();
            }
            return map_to_erlang_void();
        default:
            logger.log
                (Level.SEVERE,
                 "*** Error: JavaErlang: \nTag " + tag
                 + " not recognized");
            throw new Exception();
        }
    }

    OtpErlangObject create_thread() {
        final ThreadMsgHandler th = ThreadMsgHandler
            .createThreadMsgHandler(this);
        return map_new_thread_to_erlang(th);
    }

    static void stop_thread(final ThreadMsgHandler th) throws Exception {
        th.queue.put(mkStopThreadMsg());
    }

    static OtpErlangObject mkStopThreadMsg() {
        OtpErlangObject nullObj = map_to_erlang_null();
        return makeErlangTuple(new OtpErlangLong(Tags.stopThreadTag),
                               nullObj, nullObj, nullObj);
    }

    public static OtpErlangTuple makeErlangTuple(
                                                 final OtpErlangObject... arguments) {
        return new OtpErlangTuple(arguments);
    }

    public static OtpErlangObject makeErlangKey(final String tag,
                                                final IntKey key, final OtpErlangObject nodeId) {
        return makeErlangTuple(new OtpErlangAtom(tag),
                               new OtpErlangInt(key.key()), nodeId);
    }

    Object java_value_from_erlang(final OtpErlangObject value) throws Exception {
        if (value instanceof OtpErlangAtom) {
            final String stringValue = ((OtpErlangAtom) value).atomValue();
            if (stringValue.equals("null")) {
                return null;
            }
            if (stringValue.equals("true")) {
                return Boolean.valueOf(true);
            }
            if (stringValue.equals("false")) {
                return Boolean.valueOf(false);
            }
	    if (logger.isLoggable(Level.WARNING))
		logger.log(Level.WARNING,"java_value_from_erlang: " + value);
            throw new Exception();
        }

        if (value instanceof OtpErlangTuple) {
            OtpErlangTuple t = (OtpErlangTuple) value;
            final int arity = t.arity();

            if (arity == 2) {
                final OtpErlangObject arg = t.elementAt(1);
                if (t.elementAt(0) instanceof OtpErlangAtom) {
                    final String classSpecifier = ((OtpErlangAtom) t
                                                   .elementAt(0)).atomValue();
                    if (classSpecifier.equals("int")) {
                        return convert_to_integer(arg,false);
                    } else if (classSpecifier.equals("long")) {
                      return convert_to_long(arg,false);
                    } else if (classSpecifier.equals("short")) {
                      return convert_to_short(arg,false);
                    } else if (classSpecifier.equals("char")) {
                      return convert_to_character(arg,false);
                    } else if (classSpecifier.equals("byte")) {
                      return convert_to_byte(arg,false);
                    } else if (classSpecifier.equals("float")) {
                      return convert_to_float(arg,false);
                    } else if (classSpecifier.equals("double")) {
                      return convert_to_double(arg,false);
                    }
                } else {
                    t = (OtpErlangTuple) t.elementAt(0);
                    final String classSpecifier = ((OtpErlangAtom) t
                                                   .elementAt(0)).atomValue();
                    if (classSpecifier.equals("array")) {
                        final Class comp = (Class) fromErlType(t.elementAt(1));
                        final int dimensions = ((OtpErlangLong) t.elementAt(2))
                            .intValue();
                        final int[] arr_dimensions = 
			    checkDimensions(dimensions, arg);
                        if (logger.isLoggable(Level.FINER)) {
                          String logResult = "";
                          logResult += "Dimensions: ";
                          for (int i=0; i<arr_dimensions.length; i++)
                            logResult += "["+arr_dimensions[i]+"]";
                          logger.log(Level.FINER,logResult);
                        }
                        final Object array =
			  Array.newInstance(comp,arr_dimensions);
                        initializeArray(array, arg, comp, dimensions);
                        return array;
                    }
                }
            } else if (arity == 5) {
                final String tag = ((OtpErlangAtom) t.elementAt(0)).atomValue();
                if (tag.equals("object")) {
                    final JavaObjectKey key = objectKeyFromErlang(t);
                    final JavaObjectEntry entry = fromErlangMap.get(key);
		    if (entry == null) {
		      if (logger.isLoggable(Level.SEVERE)) {
			logger.log
			  (Level.SEVERE,
			   "\n\rTranslating " + value + " key="+key+
			   " from "+t+" could not be found in fromErlangMap from "+Thread.currentThread());
			  }
                      throw new Exception("missing fromErlang entry");
		    }
                    final Object result = entry.object();
                    if (result == null) {
                        if (logger.isLoggable(Level.FINE)) {
                            logger.log(Level.FINE,"\rTranslating " + value);
                        }
                        throw new Exception();
                    }
                    return result;
                } else if (tag.equals("executable")) {
                    final Object result = accFromErlangMap.get(t);
                    if (result == null) {
                        if (logger.isLoggable(Level.FINE)) {
                            logger.log(Level.FINE,"\rTranslating " + value);
                        }
                        throw new Exception();
                    }
                    return result;
                }
            }
        }
	if (logger.isLoggable(Level.WARNING))
	    logger.log(Level.WARNING,"java_value_from_erlang: " + value);
        throw new Exception();
    }

    Object[] java_values_from_erlang(final OtpErlangObject[] values,
                                     final Type[] types) throws Exception {
        final Object[] objects = new Object[values.length];
        for (int i = 0; i < values.length; i++) {
            final Object value = java_value_from_erlang(values[i], types[i]);
            objects[i] = value;
        }
        return objects;
    }

    Object java_value_from_erlang(Object value, final Type type)
        throws Exception {

        if (logger.isLoggable(Level.FINE)) {
            logger.log(Level.FINE,"\rvalue_from_erlang " + value +
                       " type "+toString(type));
        }

        if (value instanceof OtpErlangAtom) {
	    OtpErlangAtom a = (OtpErlangAtom) value;
            return java_value_from_erlang(a);
        }

        if (value instanceof OtpErlangTuple) {
	    OtpErlangTuple t = (OtpErlangTuple) value;
            value = java_value_from_erlang(t);
        }

        // We have to use type information to interpret the value
        if (type == java.lang.Integer.TYPE) {
          return convert_to_integer(value, false);
        } else if (type == java.lang.Integer.class) {
          // Permit null as an integer value
          return convert_to_integer(value, true);
        }
        else if (type == java.lang.Long.TYPE) {
            return convert_to_long(value,false);
        } if (type == java.lang.Long.class) {
            return convert_to_long(value,true);
        } else if (type == java.lang.Short.TYPE) {
            return convert_to_short(value,false);
        } if (type == java.lang.Short.class) {
            return convert_to_short(value,true);
        } else if (type == java.lang.Character.TYPE) {
            return convert_to_character(value,false);
        } if (type == java.lang.Character.class) {
            return convert_to_character(value,true);
        } else if (type == java.lang.Byte.TYPE) {
            return convert_to_byte(value,false);
        } if (type == java.lang.Byte.class) {
            return convert_to_byte(value,true);
        } else if (type == java.lang.Float.TYPE) {
            return convert_to_float(value,false);
        } if (type == java.lang.Float.class) {
            return convert_to_float(value,true);
        } else if (type == java.lang.Double.TYPE) {
            return convert_to_double(value,false);
        } if (type == java.lang.Double.class) {
            return convert_to_double(value,true);
        } else if (type == java.lang.Void.TYPE || type == java.lang.Void.class) {
            return convert_to_void(value);
        } else if (value instanceof OtpErlangLong) {
            return convert_to_integer(value,false);
        } else if (value instanceof OtpErlangDouble) {
           return convert_to_double(value,false);
        } else if ((value instanceof OtpErlangString) &&
                   (type == java.lang.String.class)) {
            return ((OtpErlangString) value).stringValue();
        } else if ((value instanceof OtpErlangString) &&
                   (type == java.lang.CharSequence.class)) {
            return ((OtpErlangString) value).stringValue();
        } else if ((value instanceof OtpErlangList) &&
                   isIntegerList((OtpErlangList) value) &&
                   (type == java.lang.String.class)) {
            return ((OtpErlangList) value).stringValue();
        } else {
            if (type instanceof Class) {
                final Class typeClass = (Class) type;
                final int dimensions = dimensions(typeClass);

		if (logger.isLoggable(Level.FINE)) {
		    logger.log
			(Level.FINE,
			 "typeClass="+typeClass+
			 ", dimensions="+dimensions+
			 ", value="+value);
		}

		if (dimensions > 0) {
		    if (typeClass.isArray()) {
			final Class arrElement = 
			    getArrayElementClass(typeClass);
			final int[] lengths = 
			    checkDimensions(dimensions, value);
                        if (logger.isLoggable(Level.FINER)) {
                          String logResult = "";
                          logResult += "Dimensions: ";
                          for (int i=0; i<lengths.length; i++)
                            logResult += "["+lengths[i]+"]";
                          logger.log(Level.FINER,logResult);
                        }
			final Object arr = 
			    Array.newInstance(arrElement, lengths);
			initializeArray(arr, value, arrElement, dimensions);
			return arr;
		    } else {
			if (logger.isLoggable(Level.FINE)) {
			    logger.log
				(Level.FINE,
				 "Cannot convert " + value + " to type "
				 + toString(typeClass) +
				 " dimensions = "+dimensions);
			}
			throw new Exception();
		    }
		}
		return value;
            }

            if (logger.isLoggable(Level.FINE)) {
                logger.log
                    (Level.FINE,"Cannot convert " + value + " to type "
                     + toString(type));
            }

            throw new Exception();
        }
    }

    static boolean isIntegerList(final OtpErlangList l) {
      for (OtpErlangObject obj : l)
        if (!(obj instanceof OtpErlangLong)) {
          return false;
        }
      return true;
    }

    static Object convert_to_character(final Object value, boolean permitNull)
        throws Exception {

        if (value == null && permitNull)
          return null;

        if (value instanceof OtpErlangLong) {
            return ((OtpErlangLong) value).charValue();
        } else if (value instanceof Character) {
	    return value;
	}

	if (logger.isLoggable(Level.FINE)) {
	    logger.log(Level.FINE,"\rerror: convert_to_character " + value);
	    logger.log(Level.FINE,"\rtype is " + toString(value.getClass()));
	}
        throw new Exception();
    }

    static Object convert_to_byte(final Object value, boolean permitNull) throws Exception {
        if (value == null && permitNull)
          return null;

        if (value instanceof OtpErlangLong) {
            return ((OtpErlangLong) value).byteValue();
        } else if (value instanceof Byte) {
	    return value;
	}

	if (logger.isLoggable(Level.FINE)) {
	    logger.log(Level.FINE,"\rerror: convert_to_byte " + value);
	    logger.log(Level.FINE,"\rtype is " + toString(value.getClass()));
	}
        throw new Exception();
    }

    static Object convert_to_float(final Object value, boolean permitNull)
        throws Exception {

        if (value == null && permitNull)
          return null;

        if (value instanceof OtpErlangDouble) {
            return ((OtpErlangDouble) value).floatValue();
        } else if (value instanceof OtpErlangLong) {
	    return ((OtpErlangLong) value).longValue();
	} else if (value instanceof Float) {
	    return value;
	} else if (value instanceof Long) {
	    return ((Long) value).floatValue();
	} else if (value instanceof Integer) {
	    return ((Integer) value).floatValue();
	} else if (value instanceof Byte) {
	    return ((Byte) value).floatValue();
	} else if (value instanceof Short) {
	    return ((Short) value).floatValue();
	} else if (value instanceof Character) {
	    return Float.valueOf(((Character) value).charValue());
	}
	
	if (logger.isLoggable(Level.FINE)) {
	    logger.log(Level.FINE,"\rerror: convert_to_float " + value);
	    logger.log(Level.FINE,"\rtype is " + toString(value.getClass()));
	}
        throw new Exception();
    }

    static Object convert_to_double(final Object value, boolean permitNull)
        throws Exception {

        if (value == null && permitNull)
          return null;

        if (value instanceof OtpErlangDouble) {
            return ((OtpErlangDouble) value).doubleValue();
        } else if (value instanceof OtpErlangLong) {
	    return ((OtpErlangLong) value).longValue();
	} else if (value instanceof Float) {
	    return ((Float) value).doubleValue();
	} else if (value instanceof Long) {
	    return ((Long) value).floatValue();
	} else if (value instanceof Integer) {
	    return ((Integer) value).doubleValue();
	} else if (value instanceof Byte) {
	    return ((Byte) value).doubleValue();
	} else if (value instanceof Short) {
	    return ((Short) value).doubleValue();
	} else if (value instanceof Character) {
	    return Double.valueOf(((Character) value).charValue());
	}
	
	if (logger.isLoggable(Level.FINE)) {
	    logger.log(Level.FINE,"\rerror: convert_to_double " + value);
	    logger.log(Level.FINE,"\rtype is " + toString(value.getClass()));
	}
        throw new Exception();
    }

    static Object convert_to_void(final Object value) throws Exception {
	if (logger.isLoggable(Level.FINE)) {
	    logger.log(Level.FINE,"\rerror: convert_to_void " + value);
	    logger.log(Level.FINE,"\rtype is " + toString(value.getClass()));
	}
        throw new Exception();
    }

    static Object convert_to_short(final Object value, boolean permitNull)
        throws Exception {

        if (value == null && permitNull)
          return null;

        if (value instanceof OtpErlangLong) {
            return ((OtpErlangLong) value).shortValue();
	} else if (value instanceof Byte) {
	    return ((Byte) value).shortValue();
	} else if (value instanceof Short) {
	    return value;
	}
	
	if (logger.isLoggable(Level.FINE)) {
	    logger.log(Level.FINE,"\rerror: convert_to_short " + value);
	    logger.log(Level.FINE,"\rtype is " + toString(value.getClass()));
	}
        throw new Exception();
    }

    static Object convert_to_integer(final Object value, boolean permitNull)
        throws Exception {

        if (value == null && permitNull)
          return null;

        if (value instanceof OtpErlangLong) {
            return ((OtpErlangLong) value).intValue();
        } else if (value instanceof Integer) {
	    return value;
	} else if (value instanceof Byte) {
	    return ((Byte) value).intValue();
	} else if (value instanceof Short) {
	    return ((Short) value).intValue();
	} else if (value instanceof Character) {
	    return Integer.valueOf(((Character) value).charValue());
	}
	
	if (logger.isLoggable(Level.FINE)) {
	    logger.log(Level.FINE,"\rerror: convert_to_integer " + value);
	    logger.log(Level.FINE,"\rtype is " + toString(value.getClass()));
	}
        throw new Exception();
    }

    static Object convert_to_long(final Object value, boolean permitNull) throws Exception {
        if (value == null && permitNull)
          return null;

        if (value instanceof OtpErlangLong) {
            return ((OtpErlangLong) value).longValue();
        } else if (value instanceof Long) {
	    return value;
	} else if (value instanceof Integer) {
	    return ((Integer) value).longValue();
	} else if (value instanceof Byte) {
	    return ((Byte) value).longValue();
	} else if (value instanceof Short) {
	    return ((Short) value).longValue();
	} else if (value instanceof Character) {
	    return Long.valueOf(((Character) value).charValue());
	}
	
	if (logger.isLoggable(Level.FINE)) {
	    logger.log(Level.FINE,"\rerror: convert_to_long " + value);
	    logger.log(Level.FINE,"\rtype is " + toString(value.getClass()));
	}
        throw new Exception();
    }

    static Object[] elements(final Object t) throws Exception {
        if (t instanceof OtpErlangList) {
            return ((OtpErlangList) t).elements();
        } else if (t instanceof OtpErlangTuple) {
            return ((OtpErlangTuple) t).elements();
        } else if (t instanceof OtpErlangString) {
	    // Jinterface braindamage follows...
	    final String value = ((OtpErlangString) t).stringValue();
	    final byte[] bytes = value.getBytes();
	    final OtpErlangObject[] otpBytes = 
		new OtpErlangObject[bytes.length];
	    for (int i = 0; i < bytes.length; i++) {
		otpBytes[i] = new OtpErlangLong(bytes[i]);
	    }
	    return otpBytes;
	} else if (t.getClass().isArray()) {
	    Object arr[] = new Object[Array.getLength(t)];
	    for (int i=0; i<arr.length; i++)
		arr[i] = Array.get(t,i);
	    return arr;
	} else {
	    throw new Exception();
	}
    }

  void initializeArray(final Object arr,
		       final Object value,
		       final Class type,
		       final int dimensions)
        throws Exception {
        final Class arrClass = arr.getClass();

	  if (logger.isLoggable(Level.FINER))
	    logger.log
	      (Level.FINER,
	       "initializeArray: values="+value+" class="+
	       toString(arrClass));

        final int len = Array.getLength(arr);
        final Object[] elements = elements(value);
	if (len != elements.length) {
	  if (logger.isLoggable(Level.WARNING)) {
	    logger.log
	      (Level.WARNING,
	       "arr "+arr+" has length "+len+
	       "\nbut elements "+elements+
	       "\nhave length "+elements.length);
	  }
	  throw new RuntimeException();
	}

        for (int i = 0; i < len; i++) {
	  final Object element = elements[i];
	  final Object obj_at_i = Array.get(arr, i);

	  if (dimensions > 1) {
	    initializeArray(obj_at_i, element, type, dimensions-1);
	  } else {
	    Object setValue = element;
	    if (setValue != null)
	      setValue = java_value_from_erlang(setValue,type);
	    Array.set(arr, i, setValue);
	  }
	}
    }

    // For better printing of array classes
    static String toString(Type type) {
      if (type instanceof Class) {
	return toString((Class) type);
      } else {
	return type.toString();
      }
    }
  
    static String toString(Class cl) {
      if (cl.isArray()) {
	final int dimensions =
	  dimensions(cl);
	final Class arrElement = 
	  getArrayElementClass(cl);
	if (dimensions == 1) {
	  return "[" + toString(arrElement) + "]";
	} else {
	  return "[" + dimensions + ":" + toString(arrElement) + "]";
	}
      } else {
	return cl.toString();
      }
    }

    static Class getArrayElementClass(final Class arrClass) {
        if (arrClass.isArray()) {
            return getArrayElementClass(arrClass.getComponentType());
        } else {
            return arrClass;
        }
    }

    static int[] checkDimensions(int dimensions, Object value) 
	throws Exception 
    {
        final ArrayList<Integer> result = new ArrayList<Integer>();
        while (dimensions > 0) {
            final Object[] elements = elements(value);
            result.add(elements.length);
            if ((elements.length == 0)) {
              if (dimensions > 1) {
                if (logger.isLoggable(Level.WARNING)) {
                  logger.log
                    (Level.WARNING,
                     "\r\n*** array initialization of multi-dimensional array "+
                     "with zero elements\r");
                }
                throw new RuntimeException();
              }
            } else value = elements[0];
            dimensions--;
        }
        final int[] return_value = new int[result.size()];
        int i = 0;
        for (final Integer ri : result) {
            return_value[i++] = ri;
        }
        return return_value;
    }

    static int dimensions(final Class arrClass) {
        if (arrClass.isArray()) {
            return 1 + dimensions(arrClass.getComponentType());
        } else {
            return 0;
        }
    }

    static Type[] fromErlTypes(final OtpErlangObject[] erlTypes)
        throws Exception {
        final int len = erlTypes.length;
        // logger.log(Level.FINER,"\rfromErlTypes(len="+len+")");
        final Type types[] = new Type[len];
        for (int i = 0; i < len; i++) {
            types[i] = fromErlType(erlTypes[i]);
        }
        return types;
    }

    static Type fromErlType(final OtpErlangObject erlType) throws Exception {
        if (erlType instanceof OtpErlangAtom) {
            final String name = ((OtpErlangAtom) erlType).atomValue();
            if (name.equals("int")) {
                return java.lang.Integer.TYPE;
            } else if (name.equals("short")) {
                return java.lang.Short.TYPE;
            } else if (name.equals("long")) {
                return java.lang.Long.TYPE;
            } else if (name.equals("char")) {
                return java.lang.Character.TYPE;
            } else if (name.equals("boolean")) {
                return java.lang.Boolean.TYPE;
            } else if (name.equals("byte")) {
                return java.lang.Byte.TYPE;
            } else if (name.equals("float")) {
                return java.lang.Float.TYPE;
            } else if (name.equals("double")) {
                return java.lang.Double.TYPE;
            } else if (name.equals("void")) {
                return java.lang.Void.TYPE;
            } else {
                return findClass(name);
            }
        } else if (erlType instanceof OtpErlangTuple) {
            final OtpErlangTuple t = (OtpErlangTuple) erlType;
            final OtpErlangAtom a = (OtpErlangAtom) t.elementAt(0);
            if (a.atomValue().equals("array")) {
                final Class comp = (Class) fromErlType(t.elementAt(1));
                int ndimensions = 1;
                if (t.arity() == 3) {
                    ndimensions = ((OtpErlangLong) t.elementAt(2)).intValue();
                }
                final int[] dimensions = new int[ndimensions];
                for (int i = 0; i < ndimensions; i++) {
                    dimensions[i] = 0;
                }
                final Object array = Array.newInstance(comp, dimensions);
                final Class arrayClass = array.getClass();
                return arrayClass;
            }
        }
	if (logger.isLoggable(Level.WARNING)) 
	    logger.log(Level.WARNING,"\rtype " + erlType + " is not understood?");
        throw new Exception();
    }

    Class findClass(final OtpErlangObject classRef) throws Exception {
	if (classRef instanceof OtpErlangAtom) {
	    final String className = ((OtpErlangAtom) classRef).atomValue();
	    return findClass(className);
	} else {
	    Class cl = (Class) java_value_from_erlang(classRef);
	    return cl;
	}
    }

    static Class findClass(final String className) throws Exception {
        try {
            final Class c = Class.forName(className);
            return c;
        } catch (final Exception e) {  }

        final StringBuilder str = new StringBuilder(className);
        do {
            final int lastIndex = str.lastIndexOf(".");
            if (lastIndex == -1) {
		if (logger.isLoggable(Level.WARNING))
		    logger.log
			(Level.WARNING,
			 "findClass: cannot locate class " + str +
			 " using classpath\n"+System.getProperty("java.class.path")+
                     "\nworking directory is "+System.getProperty("user.dir"));
                if (logger.isLoggable(Level.WARNING)) {
                    logger.log(Level.WARNING,"findClass: cannot locate class " + str);
                }
                throw new Exception();
            }
            str.replace(lastIndex, lastIndex + 1, "$");
            try {
                final Class c = Class.forName(str.toString());
                return c;
            } catch (final Exception exc) { }

        } while (true);
    }

    public OtpErlangObject makeErlangObjectKey(final long key, final long counter, final long classNumber, final OtpErlangObject nodeId) {
        return makeErlangTuple
            (new OtpErlangAtom("object"),
             new OtpErlangLong(key),
             new OtpErlangLong(counter),
             new OtpErlangLong(classNumber),
             nodeId);
    }

    public synchronized OtpErlangObject map_to_erlang(final Object obj) {
        if (obj == null) {
            return map_to_erlang_null();
        }

        if (returnOtpErlangObject && (obj instanceof OtpErlangObject)) {
          return (OtpErlangObject) obj;
        }

        final RefEqualsObject obj_key =
            new RefEqualsObject(obj);
        final JavaObjectEntry oldValue =
            toErlangMap.get(obj_key);

        if (oldValue != null) {
            long refCount = oldValue.alias();
            if (logger.isLoggable(Level.INFO))
                logger.log
                    (Level.INFO,
                     "increasing count for "+
                     System.identityHashCode(oldValue.object()));
            return makeErlangObjectKey
                (oldValue.key(),refCount,oldValue.classNumber(), oldValue.nodeId());
        }

        final int newCounter = objCounter++;
        Class cl = obj.getClass();
        Integer classNumber = classMap.get(cl);
        if (classNumber == null) {
            classNumber = classCounter;
            classMap.put(cl,classCounter++);
        }
        final JavaObjectEntry entry =
            new JavaObjectEntry(obj, newCounter, classNumber, nodeIdentifier);
        if (logger.isLoggable(Level.INFO))
            logger.log
                (Level.INFO,
                 "returning new object "+
                 System.identityHashCode(entry.object()));
        toErlangMap.put(obj_key, entry);
        final JavaObjectKey key =
            new JavaObjectKey(newCounter,nodeIdentifier);
        fromErlangMap.put(key, entry);
        long refCount = entry.alias();
        return makeErlangObjectKey(key.key(), refCount, entry.classNumber(), key.nodeId());
    }

    public synchronized OtpErlangObject acc_map_to_erlang(final Object obj) {
        final Object obj_key = obj;
        final OtpErlangObject oldValue = accToErlangMap.get(obj_key);
        if (oldValue != null) {
            return oldValue;
        }

        final IntKey key = new IntKey(accCounter++);
        final OtpErlangObject erlangKey = makeErlangKey("executable", key,
                                                        nodeIdentifier);
        accToErlangMap.put(obj_key, erlangKey);
        accFromErlangMap.put(erlangKey, obj);
        return erlangKey;
    }

    synchronized OtpErlangObject map_new_thread_to_erlang(final ThreadMsgHandler th) {
        final IntKey key = new IntKey(threadCounter++);
        final OtpErlangObject erlangKey = makeErlangKey("thread", key,
                                                        nodeIdentifier);
        threadMap.put(erlangKey, th);
        return erlangKey;
    }

    public OtpErlangObject map_to_erlang(final Object obj, final Class classType)
        throws Exception {
        if (classType == java.lang.Integer.TYPE) {
            return map_to_erlang_int(((Integer) obj).intValue());
        } else if (classType == java.lang.Short.TYPE) {
            return map_to_erlang_short(((Short) obj).shortValue());
        } else if (classType == java.lang.Long.TYPE) {
            return map_to_erlang_long(((Long) obj).longValue());
        } else if (classType == java.lang.Byte.TYPE) {
            return map_to_erlang_byte(((Byte) obj).byteValue());
        } else if (classType == java.lang.Boolean.TYPE) {
            return map_to_erlang_boolean(((Boolean) obj).booleanValue());
        } else if (classType == java.lang.Character.TYPE) {
            return map_to_erlang_character(((Character) obj).charValue());
        } else if (classType == java.lang.Float.TYPE) {
            return map_to_erlang_float(((Float) obj).floatValue());
        } else if (classType == java.lang.Double.TYPE) {
            return map_to_erlang_double(((Double) obj).doubleValue());
        } else if (classType == java.lang.Void.TYPE) {
            return map_to_erlang_void();
        } else {
            return map_to_erlang(obj);
        }
    }

    public OtpErlangObject map_to_erlang(final Object obj, final int pos,
                                         final Class classType) throws Exception {
        if (classType == java.lang.Integer.TYPE) {
            return map_to_erlang_long(Array.getInt(obj, pos));
        } else if (classType == java.lang.Short.TYPE) {
            return map_to_erlang_long(Array.getShort(obj, pos));
        } else if (classType == java.lang.Long.TYPE) {
            return map_to_erlang_long(Array.getLong(obj, pos));
        } else if (classType == java.lang.Byte.TYPE) {
            return map_to_erlang_long(Array.getByte(obj, pos));
        } else if (classType == java.lang.Boolean.TYPE) {
            return map_to_erlang_boolean(Array.getBoolean(obj, pos));
        } else if (classType == java.lang.Character.TYPE) {
            return map_to_erlang_character(Array.getChar(obj, pos));
        } else if (classType == java.lang.Float.TYPE) {
            return map_to_erlang_double(Array.getFloat(obj, pos));
        } else if (classType == java.lang.Double.TYPE) {
            return map_to_erlang_double(Array.getDouble(obj, pos));
        } else if (classType == java.lang.Void.TYPE) {
            return map_to_erlang_void();
        } else {
            return map_to_erlang(Array.get(obj, pos));
        }
    }

    public static OtpErlangObject map_to_erlang_long(final long value) {
        return new OtpErlangLong(value);
    }

    public static OtpErlangObject map_to_erlang_int(final int value) {
        return new OtpErlangInt(value);
    }

    public static OtpErlangObject map_to_erlang_short(final short value) {
        return new OtpErlangShort(value);
    }

    public static OtpErlangObject map_to_erlang_byte(final byte value) {
        return new OtpErlangByte(value);
    }

    public static OtpErlangObject map_to_erlang_character(final char value) {
        return new OtpErlangChar(value);
    }

    public static OtpErlangObject map_to_erlang_double(final double value) {
        if (Double.isNaN(value) || Double.isInfinite(value))
          throw new ArithmeticException("Can't represent infinite or NaN doubles");

        return new OtpErlangDouble(value);
    }

    public static OtpErlangObject map_to_erlang_float(final float value) {
        if (Float.isNaN(value) || Float.isInfinite(value))
            throw new ArithmeticException("Can't represent infinite or NaN floats");

        return new OtpErlangDouble(value);
    }

    public static OtpErlangObject map_to_erlang_boolean(final boolean value) {
        return new OtpErlangBoolean(value);
    }

    public static OtpErlangObject map_to_erlang_void() {
        return new OtpErlangAtom("void");
    }

    public static OtpErlangObject map_to_erlang_null() {
        return new OtpErlangAtom("null");
    }

    public JavaObjectKey objectKeyFromErlang(OtpErlangObject obj) {
        final OtpErlangTuple tuple = (OtpErlangTuple) obj;
        final OtpErlangLong key = (OtpErlangLong) tuple.elementAt(1);
        return new JavaObjectKey(key.longValue(),nodeIdentifier);
    }

    synchronized OtpErlangObject free(final OtpErlangObject arg) {
        final JavaObjectKey key = objectKeyFromErlang(arg);
        final JavaObjectEntry entry = fromErlangMap.get(key);
        final RefEqualsObject objKey = new RefEqualsObject(entry.object());
        toErlangMap.remove(objKey);
        fromErlangMap.remove(key);
        return new OtpErlangBoolean(true);
    }

    synchronized OtpErlangObject freeInstance(final OtpErlangObject arg) {
        final JavaObjectKey key = objectKeyFromErlang(arg);
        final JavaObjectEntry entry = fromErlangMap.get(key);
        if (entry.free() <= 0) {
            if (logger.isLoggable(Level.INFO))
                logger.log
                    (Level.INFO,
                     "freeing "+System.identityHashCode(entry.object()));
            final RefEqualsObject objKey = new RefEqualsObject(entry.object());

            if (toErlangMap.remove(objKey) == null) {
              if (logger.isLoggable(Level.SEVERE))
                logger.log
                  (Level.SEVERE,
                   "Could not remove "+objKey+" from toErlangMap; entry="+entry+" arg="+arg);
            }

            if (fromErlangMap.remove(key) == null) {
              if (logger.isLoggable(Level.SEVERE))
                logger.log
                  (Level.SEVERE,
                   "Could not remove "+key+" from fromErlangMap; entry="+entry+" arg="+arg);
            }
            return new OtpErlangBoolean(true);
        } else {
            if (logger.isLoggable(Level.INFO))
                logger.log
                    (Level.INFO,
                     System.identityHashCode(entry.object())+
                     " has "+entry.references()+" references");
            return new OtpErlangBoolean(false);
        }
    }

    synchronized OtpErlangObject memoryUsage(final OtpErlangObject arg) {
        int N = toErlangMap.size();
        int M = fromErlangMap.size();
        if (N != M && logger.isLoggable(Level.WARNING))
            logger.log
                (Level.WARNING,
                 "Warning: from table has size "+M+"=/="+N+" to table");
        return new OtpErlangInt(N);
    }

    OtpErlangObject identity(final OtpErlangObject arg) {
        try {
            final Object obj = java_value_from_erlang(arg);
            return map_to_erlang(obj,obj.getClass());
        } catch (Exception e) { return map_to_erlang_null(); }
    }

    OtpErlangObject objTypeCompat(final OtpErlangObject cmd) throws Exception {
        final OtpErlangTuple tuple = (OtpErlangTuple) cmd;
        final OtpErlangObject[] alternatives = ((OtpErlangTuple) tuple
                                                .elementAt(0)).elements();
        final OtpErlangObject[] objs = ((OtpErlangTuple) tuple.elementAt(1))
            .elements();

	if (logger.isLoggable(Level.FINER))
	    logger.log(Level.FINER,"objTypeCompat: "+cmd);

        for (int i = 0; i < objs.length; i++) {
            final Type t = fromErlType(alternatives[i]);
            final Class tc = (Class) t;
            if (!is_acceptable_as_argument(objs[i], tc)) {
                return new OtpErlangBoolean(false);
            }
        }
        return new OtpErlangBoolean(true);
    }

    synchronized OtpErlangObject lookupClass(final OtpErlangObject cmd) throws Exception {
	final Class cl = findClass(cmd);
	final Integer classNumber = classMap.get(cl);
	
	if (classNumber != null) {
	    return new OtpErlangLong(classNumber);
	} else {
	    classMap.put(cl,classCounter);
	    return new OtpErlangLong(classCounter++);
	}
    }

    OtpErlangObject getConstructor(final OtpErlangObject cmd) throws Exception {
        final OtpErlangTuple t = (OtpErlangTuple) cmd;
        final OtpErlangTuple typeList = (OtpErlangTuple) t.elementAt(2);
        final Constructor cnstr =
	    getConstructor(t.elementAt(0),
			   typeList.elements(),
			   ((OtpErlangAtom) t.elementAt(3)).booleanValue());
        if (logger.isLoggable(Level.FINER)) {
            logger.log(Level.FINER,"\rcmd " + cmd + " has typelist "
                       + typeList.elements());
        }
        return acc_map_to_erlang(cnstr);
    }

    OtpErlangObject getField(final OtpErlangObject cmd) throws Exception {
        final OtpErlangTuple t = (OtpErlangTuple) cmd;
        final Field field = getField(findClass(t.elementAt(0)),
                                     ((OtpErlangAtom) t.elementAt(1)).atomValue(),
				     ((OtpErlangAtom) t.elementAt(3)).booleanValue());
        return acc_map_to_erlang(field);
    }

    public static String add_className(String locationStr,
                                       final String className) {
        final String separator = System.getProperty("file.separator");
        if (locationStr.endsWith(separator)) {
            locationStr = locationStr.substring(0, locationStr.length() - 1);
        }
        final String[] classParts = className.split("\\.");
        String retvalue = locationStr;
        for (final String part : classParts) {
            retvalue = retvalue + separator + part;
        }
        return retvalue + ".class";
    }

    public static String getExtension(final String s) {

        final String separator = System.getProperty("file.separator");
        String filename;

        // Remove the path upto the filename.
        final int lastSeparatorIndex = s.lastIndexOf(separator);
        if (lastSeparatorIndex == -1) {
            filename = s;
        } else {
            filename = s.substring(lastSeparatorIndex + 1);
        }

        // Remove the extension.
        final int extensionIndex = filename.lastIndexOf(".");
        if (extensionIndex == -1) {
            return "";
        }

        return filename.substring(extensionIndex);
    }

    OtpErlangObject getConstructors(final OtpErlangObject cmd)
        throws Exception {
        final OtpErlangTuple t = (OtpErlangTuple) cmd;
        final boolean observerInClass = ((OtpErlangAtom) t.elementAt(1))
            .booleanValue();
        final Class cl = findClass(t.elementAt(0));
        final Constructor[] constructors = cl.getConstructors();
        final ArrayList<OtpErlangTuple> erlConstructors = new ArrayList<OtpErlangTuple>();

        for (final Constructor constructor : constructors) {
            final int modifiers = constructor.getModifiers();
	    final OtpErlangAtom name = 
		new OtpErlangAtom(constructor.getName());
	    final Type[] parameterTypes = constructor.getParameterTypes();
	    final OtpErlangObject[] erlTypes = new OtpErlangObject[parameterTypes.length];
	    for (int i = 0; i < parameterTypes.length; i++) {
		erlTypes[i] = toErlType(parameterTypes[i]);
	    }
	    erlConstructors.add(makeErlangTuple(name, new OtpErlangList(erlTypes)));
        }

	if (observerInClass) {
	    final Constructor[] declaredConstructors = cl.getDeclaredConstructors();
	    for (final Constructor constructor : declaredConstructors) {
		if (constructor.isSynthetic()) {
		    if (logger.isLoggable(Level.FINER)) {
			logger.log(Level.FINER,"Skipping synthetic or bridge constructor "
				   + constructor + " in class " + cl);
		    }
		    continue;
		}
		final int modifiers = constructor.getModifiers();
		if ((modifiers & Modifier.PUBLIC) != 0) continue;
		if (is_executable(modifiers)) {
		    final OtpErlangAtom name = new OtpErlangAtom(constructor.getName());
		    final Type[] parameterTypes = constructor.getParameterTypes();
		    final OtpErlangObject[] erlTypes = new OtpErlangObject[parameterTypes.length];
		    for (int i = 0; i < parameterTypes.length; i++) {
			erlTypes[i] = toErlType(parameterTypes[i]);
		    }
		    erlConstructors.add(makeErlangTuple(name, new OtpErlangList(erlTypes)));
                    constructor.setAccessible(true);
		} else if (logger.isLoggable(Level.FINER)) {
		    logger.log(Level.FINER,"\rConstructor is not visible to us");
		}
	    }
	}


	
        final OtpErlangTuple[] tmp_arr =
	    new OtpErlangTuple[erlConstructors.size()];
        for (int i = 0; i < erlConstructors.size(); i++) {
            tmp_arr[i] = erlConstructors.get(i);
        }
        return new OtpErlangList(tmp_arr);
    }

    OtpErlangObject getMethods(final OtpErlangObject cmd)
            throws Exception {
        final OtpErlangTuple t = (OtpErlangTuple) cmd;
	final boolean selectStatics = ((OtpErlangAtom) t.elementAt(1))
                .booleanValue(); 
        final boolean observerInClass = ((OtpErlangAtom) t.elementAt(2))
                .booleanValue();
        final Class cl = findClass(t.elementAt(0));
        final ArrayList<OtpErlangTuple> erlMethods = new ArrayList<OtpErlangTuple>();
        final Method[] publicMethods = cl.getMethods();
        for (final Method method : publicMethods) {
          /*
            if (method.isBridge() || method.isSynthetic()) {
                if (logger.isLoggable(Level.FINER)) {
                    logger.log(Level.FINER,"Skipping synthetic or bridge method "
			       + method + " in class " + toString(cl));
                    logger.log(Level.FINER,"isBridge: "+method.isBridge()+
                               " isSynthetic: "+method.isSynthetic());
                }
                continue;
            } else {
                if (logger.isLoggable(Level.FINER))
                  logger.log(Level.FINER,"Found method "+method);
            }
          */
            
            final int modifiers = method.getModifiers();
            if (is_static(modifiers) != selectStatics) continue;
            if (is_executable(modifiers)) {
                final OtpErlangAtom name = new OtpErlangAtom(method.getName());
                final Type[] parameterTypes = method.getParameterTypes();
                final OtpErlangObject[] erlTypes = new OtpErlangObject[parameterTypes.length];
                for (int i = 0; i < parameterTypes.length; i++) {
                    erlTypes[i] = toErlType(parameterTypes[i]);
                }
                erlMethods.add(makeErlangTuple(name, new OtpErlangList(erlTypes)));
            } else if (logger.isLoggable(Level.FINER)) {
                logger.log(Level.FINER,"\rMethod is not visible to us");
            }
        }

	if (observerInClass) {
	    final Method[] declaredMethods = cl.getDeclaredMethods();
	    for (final Method method : declaredMethods) {
              /*
		if (method.isBridge() || method.isSynthetic()) {
		    if (logger.isLoggable(Level.FINER)) {
			logger.log(Level.FINER,"Skipping synthetic or bridge method "
				   + method + " in class " + toString(cl));
		    }
		    continue;
		}
              */
		final int modifiers = method.getModifiers();
		if ((modifiers & Modifier.PUBLIC) != 0) continue;
		if (is_static(modifiers) != selectStatics) continue;
		if (is_executable(modifiers)) {
		    final OtpErlangAtom name = new OtpErlangAtom(method.getName());
		    final Type[] parameterTypes = method.getParameterTypes();
		    final OtpErlangObject[] erlTypes = new OtpErlangObject[parameterTypes.length];
		    for (int i = 0; i < parameterTypes.length; i++) {
			erlTypes[i] = toErlType(parameterTypes[i]);
		    }
		    erlMethods.add(makeErlangTuple(name, new OtpErlangList(erlTypes)));
                    method.setAccessible(true);
		} else if (logger.isLoggable(Level.FINER)) {
		    logger.log(Level.FINER,"\rMethod is not visible to us");
		}
	    }
	}

        final OtpErlangTuple[] tmp_arr = new OtpErlangTuple[erlMethods.size()];
        for (int i = 0; i < erlMethods.size(); i++) {
            tmp_arr[i] = erlMethods.get(i);
        }
        return new OtpErlangList(tmp_arr);
    }

    OtpErlangObject getClasses(final OtpErlangObject cmd)
        throws Exception {
        final OtpErlangTuple t = (OtpErlangTuple) cmd;
        final Class cl = findClass(t.elementAt(0));
        final Class[] classes = cl.getClasses();
        final ArrayList<OtpErlangAtom> erlClasses = new ArrayList<OtpErlangAtom>();
        for (final Class cl_cand : classes) {
            final int modifiers = cl_cand.getModifiers();

            if (!is_interface(modifiers)) {
                final OtpErlangAtom name = new OtpErlangAtom(cl_cand.getName());
                erlClasses.add(name);
            }
        }
        final OtpErlangAtom[] tmp_arr = new OtpErlangAtom[erlClasses.size()];
        for (int i = 0; i < erlClasses.size(); i++) {
            tmp_arr[i] = erlClasses.get(i);
        }
        return new OtpErlangList(tmp_arr);
    }

    OtpErlangObject getFields(final OtpErlangObject cmd)
            throws Exception {
        final OtpErlangTuple t = (OtpErlangTuple) cmd;
	final boolean selectStatics = ((OtpErlangAtom) t.elementAt(1))
                .booleanValue(); 
        final boolean observerInClass = ((OtpErlangAtom) t.elementAt(2))
                .booleanValue();
        final Class cl = findClass(t.elementAt(0));
        final ArrayList<OtpErlangTuple> erlFields =
	    new ArrayList<OtpErlangTuple>();
        final Field[] publicFields = cl.getFields();
        for (final Field field : publicFields) {
            final int modifiers = field.getModifiers();
            if (is_static(modifiers) != selectStatics) continue;

            if (field.isSynthetic()) {
                if (logger.isLoggable(Level.FINER)) {
                    logger.log(Level.FINER,"Skipping synthetic or bridge field "
			       + field + " in class " + toString(cl));
                }
                continue;
            }
            final OtpErlangAtom name = new OtpErlangAtom(field.getName());
            final OtpErlangObject fieldType = toErlType(field.getType());
            erlFields.add(makeErlangTuple(name, new OtpErlangList(fieldType)));
        }

	if (observerInClass) {
	    final Field[] declaredFields = cl.getDeclaredFields();
	    for (final Field field : declaredFields) {
		final int modifiers = field.getModifiers();
		if (is_static(modifiers) != selectStatics) continue;
		if ((modifiers & Modifier.PUBLIC) != 0) continue;
		if (field.isSynthetic()) {
		    if (logger.isLoggable(Level.FINER)) {
			logger.log(Level.FINER,"Skipping synthetic or bridge field "
				   + field + " in class " + toString(cl));
		    }
		    continue;
		}
		final OtpErlangAtom name = new OtpErlangAtom(field.getName());
		final OtpErlangObject fieldType = toErlType(field.getType());
		erlFields.add(makeErlangTuple(name, new OtpErlangList(fieldType)));		
		field.setAccessible(true);
	    }
	}

        final OtpErlangTuple[] tmp_arr = new OtpErlangTuple[erlFields.size()];
        for (int i = 0; i < erlFields.size(); i++) {
            tmp_arr[i] = erlFields.get(i);
        }
        return new OtpErlangList(tmp_arr);
    }

    static boolean is_executable(final int modifier) {
        return (modifier & Modifier.ABSTRACT) == 0;
    }

    static boolean is_interface(final int modifier) {
        return (modifier & Modifier.INTERFACE) != 0;
    }

    static boolean is_static(final int modifier) {
        return (modifier & Modifier.STATIC) != 0;
    }

    Field get_field(final OtpErlangObject obj) throws Exception {
        final Object result = accFromErlangMap.get(obj);
        if (result instanceof Field) {
            return (Field) result;
        }
        throw new Exception();
    }

    OtpErlangObject getMethod(final OtpErlangObject cmd) throws Exception {
        final OtpErlangTuple t = (OtpErlangTuple) cmd;
        final String methodName = ((OtpErlangAtom) t.elementAt(1)).atomValue();
        final OtpErlangTuple typeList = (OtpErlangTuple) t.elementAt(2);
        final OtpErlangAtom observerInClass = (OtpErlangAtom) t.elementAt(3);
        final Method method = getMethod(findClass(t.elementAt(0)), methodName,
					typeList.elements(),observerInClass.booleanValue());
	return acc_map_to_erlang(method);
    }

    Constructor getConstructor(final OtpErlangObject classRef,
			       final OtpErlangObject[] erlTypes,
			       final boolean observerInClass) throws Exception {
        final Class cl = findClass(classRef);
        final Type[] types = fromErlTypes(erlTypes);

        for (final Constructor cnstr : cl.getConstructors()) {
            if (checkTypes(cnstr.getParameterTypes(), types)) {
                // Fix for java bug 4071957
                if (cl.isMemberClass()) {
                    cnstr.setAccessible(true);
                }
                return cnstr;
            }
        }

	if (observerInClass) {
	    for (final Constructor cnstr : cl.getDeclaredConstructors()) {
		if (checkTypes(cnstr.getParameterTypes(), types)) {
		    cnstr.setAccessible(true);
		    return cnstr;
		} 
	    }
	}
	
	if (logger.isLoggable(Level.FINE))
	    logger.log(Level.FINE,"No constructor found for " + cl.getName() + ":");
        printObjectArray(types);
	if (logger.isLoggable(Level.FINER)) {
	    logger.log(Level.FINER,"");
	    logger.log(Level.FINER,"Available constructors: ");
	    for (final Constructor cnstr : cl.getConstructors()) {
		logger.log(Level.FINER,"constructor: ");
		printObjectArray(cnstr.getParameterTypes());
	    }
	    logger.log(Level.FINER,"\r------------------------");
	}

        throw new Exception();
    }

    static Field getField(final Class cl, final String fieldName,final boolean observerInClass)
        throws Exception {
        for (final Field field : cl.getFields()) {
            if (field.getName().equals(fieldName)) {
                // Fix for java bug 4071957
                if (cl.isMemberClass()) {
                    field.setAccessible(true);
                }
                return field;
            }
        }
	if (observerInClass) {
	    for (final Field field : cl.getDeclaredFields()) {
		if (field.getName().equals(fieldName)) {
		    field.setAccessible(true);
		    return field;
		}
	    }
	}
	
	if (logger.isLoggable(Level.FINE))
	    logger.log(Level.FINE,"\rNo field found");
	if (logger.isLoggable(Level.FINER)) {
	    for (final Field field : cl.getFields()) {
		logger.log(Level.FINER,"\rfield: " + field.getName());
	    }
        }
        throw new Exception();
    }

    static Method getMethod(final Class cl, 
			    final String methodName,
                            final OtpErlangObject[] erlTypes,
			    final boolean observerInClass) throws Exception {
        final Type[] types = fromErlTypes(erlTypes);

        for (final Method method : cl.getMethods()) {
            if (!method.getName().equals(methodName)) {
                continue;
            }
            if (checkTypes(method.getParameterTypes(), types)) {
                // Fix for java bug 4071957
                if (cl.isMemberClass() || cl.isAnonymousClass()) {
                    method.setAccessible(true);
                }
                return method;
            }
        }

	if (observerInClass) {
	    for (final Method method : cl.getDeclaredMethods()) {
		if (!method.getName().equals(methodName)) {
		    continue;
		}
		if (checkTypes(method.getParameterTypes(), types)) {
		    // Fix for java bug 4071957
		    if (cl.isMemberClass() || cl.isAnonymousClass()) {
			method.setAccessible(true);
		    }
		    return method;
		}
	    }
	}

	if (logger.isLoggable(Level.FINE))
	    logger.log(Level.FINE,"No method found for " + cl.getName() + "."
		       + methodName + ":");
	if (logger.isLoggable(Level.FINER)) {
	    printObjectArray(types);
	    logger.log(Level.FINER,"");
	    logger.log(Level.FINER,"Available methods: ");
	    for (final Method method : cl.getMethods()) {
		if (method.getName().equals(methodName)) {
		    logger.log(Level.FINER,"method: ");
		    printObjectArray(method.getParameterTypes());
		}
	    }
	    logger.log(Level.FINER,"\r------------------------");
	}
        throw new Exception();
    }

    Object get_fun(final OtpErlangObject cmd) throws Exception {
        final Object result = accFromErlangMap.get(cmd);
        if (result instanceof Method || result instanceof Constructor) {
            return result;
        }
	if (logger.isLoggable(Level.FINE))
	    logger.log(Level.FINE,cmd + " is not a method/constructor");
	if (logger.isLoggable(Level.FINER)) {
	    final Set<OtpErlangObject> keys = accFromErlangMap.keySet();
	    logger.log(Level.FINER,"\rMap contains:");
	    for (final OtpErlangObject key : keys) {
		logger.log(Level.FINER,key + ",");
	    }
	}
        throw new Exception();
    }

    static boolean is_integer_like(final OtpErlangObject value) {
        return value instanceof OtpErlangLong || value instanceof OtpErlangInt
            || value instanceof OtpErlangShort
            || value instanceof OtpErlangChar
            || value instanceof OtpErlangByte;
    }

    static boolean is_float_like(final OtpErlangObject value) {
        return value instanceof OtpErlangFloat
            || value instanceof OtpErlangDouble;
    }

    @SuppressWarnings("unchecked")
    boolean is_acceptable_as_argument(final OtpErlangObject value,
                                      final Class type) throws Exception {
        Object obj;
        boolean result;

        try {
            obj = java_value_from_erlang(value, type);
        } catch (final Exception e) {
	  if (logger.isLoggable(Level.FINER)) {
	    e.printStackTrace();
	    logger.log
	      (Level.FINER,
	       "cannot convert "+value+" into type "+toString(type));
	  }
	  return false;
        }

	if (obj == null)
	    return !is_basic_type(type);
	
        final Class normalizedType = conv_basic_type(type);
        result = obj != null && normalizedType.isAssignableFrom(obj.getClass());
	if (!result && logger.isLoggable(Level.FINER)) {
	    logger.log
		(Level.FINER,
		 value+" is not acceptable as an argument for "+toString(type));
	}
        return result;
    }

    static boolean is_basic_type(final Class type) {
	return
	    (type == java.lang.Integer.TYPE)
	    || (type == java.lang.Long.TYPE)
	    || (type == java.lang.Short.TYPE)
	    || (type == java.lang.Character.TYPE)
	    || (type == java.lang.Byte.TYPE)
	    || (type == java.lang.Float.TYPE)
	    || (type == java.lang.Double.TYPE)
	    || (type == java.lang.Boolean.TYPE)
	    || (type == java.lang.Void.TYPE);
    }

    static Class conv_basic_type(final Class type) {
        if (type == java.lang.Integer.TYPE) {
            return java.lang.Integer.class;
        }
        if (type == java.lang.Long.TYPE) {
            return java.lang.Long.class;
        }
        if (type == java.lang.Short.TYPE) {
            return java.lang.Short.class;
        }
        if (type == java.lang.Character.TYPE) {
            return java.lang.Character.class;
        }
        if (type == java.lang.Byte.TYPE) {
            return java.lang.Byte.class;
        }
        if (type == java.lang.Float.TYPE) {
            return java.lang.Float.class;
        }
        if (type == java.lang.Double.TYPE) {
            return java.lang.Double.class;
        }
        if (type == java.lang.Boolean.TYPE) {
            return java.lang.Boolean.class;
        }
        if (type == java.lang.Void.TYPE) {
            return java.lang.Void.class;
        }
        return type;
    }

    static void printObjectArray(final Object[] arr) {
	if (logger.isLoggable(Level.FINER)) {
	    for (final Object t : arr) {
		logger.log(Level.FINER,t + ", ");
	    }
        }
    }

    static OtpErlangObject toErlType(final Type t) throws Exception {
        if (t instanceof Class) {
            final Class c = (Class) t;
            if (c.isArray()) {
                return makeErlangTuple(new OtpErlangAtom("array"),
                                       new OtpErlangAtom(getArrayElementClass(c)
                                                         .getCanonicalName()), new OtpErlangLong(
                                                                                                 dimensions(c)));
            } else {
                return new OtpErlangAtom(c.getCanonicalName());
            }
        } else {
	if (logger.isLoggable(Level.WARNING))
	  logger.log(Level.WARNING,"\rCannot handle " + toString(t) + " yet");
	throw new Exception();
        }
    }

    static boolean checkTypes(final Type a1[], final Type a2[]) {
        if (a1.length != a2.length) {
            return false;
        }
        for (int i = 0; i < a1.length; i++) {
            if (!a1[i].equals(a2[i])) {
                return false;
            }
        }
        return true;
    }

    public OtpErlangObject return_value(final Object obj) throws Exception {
        if (obj instanceof OtpErlangObject) {
            return makeErlangTuple(new OtpErlangAtom("value"),
                                   (OtpErlangObject) obj);
        } else if (obj instanceof Throwable) {
            final Throwable t = (Throwable) obj;
            return makeErlangTuple(new OtpErlangAtom("exception"),
                                   map_to_erlang(t));
        }
        logger.log(Level.SEVERE,"Cannot return non-Erlang/non-Exception " + obj);
        throw new Exception();
    }

    //////////////////////////////////////////////////////////////////////
    OtpErlangObject new_proxy_class(final OtpErlangObject cmd) throws Exception {
        final OtpErlangTuple t = (OtpErlangTuple) cmd;
        final OtpErlangAtom className = (OtpErlangAtom) t.elementAt(0);
        final OtpErlangObject methods = t.elementAt(1);
        final Class cl = Class.forName(className.atomValue());
        final ProxyInstanceFactory pcl = ProxyFactoryClass.newClass(this, cl, methods);
        return map_to_erlang(pcl);
    }

    OtpErlangObject new_proxy_object(final OtpErlangObject cmd) throws Exception {
        final OtpErlangTuple t = (OtpErlangTuple) cmd;
        final ProxyInstanceFactory pif = (ProxyInstanceFactory) java_value_from_erlang(t.elementAt(0));
        final int objectId = ((OtpErlangLong) t.elementAt(1)).intValue();
        final OtpErlangPid pid = (OtpErlangPid) t.elementAt(2);
        final OtpErlangObject obj = pif.newInstance(objectId,pid);
        return obj;
    }

    OtpErlangObject proxy_reply(final OtpErlangObject cmd) throws Exception {
        final OtpErlangTuple t = (OtpErlangTuple) cmd;
        final ProxyHandler handler = (ProxyHandler) java_value_from_erlang(t.elementAt(0));
        handler.setAnswer(t.elementAt(1));
        return map_to_erlang_void();
    }

    public static JavaErlang getJavaErlang() {
	return javaErlang;
    }

    public OtpMbox getMbox() {
	return msgs;
    }

    public String getConnectedErlangNode() {
	return connectedErlangNode;
    }

    public OtpNode getNode() {
	return node;
    }

}
