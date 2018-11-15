package com.oracle.svm.core.posix;

import java.net.ProtocolFamily;
import java.net.SocketOption;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.util.HashMap;
import java.util.Map;

import static com.oracle.svm.core.posix.OsConstants.*;

class SocketOptionRegistry {                                                   
    private SocketOptionRegistry() { }                                         
    private static class RegistryKey {                                         
        private final SocketOption<?> name;                                    
        private final ProtocolFamily family;                                   
        RegistryKey(SocketOption<?> name, ProtocolFamily family) {             
            this.name = name;                                                  
            this.family = family;                                              
        }                                                                      
        public int hashCode() {                                                
            return name.hashCode() + family.hashCode();                        
        }                                                                      
        public boolean equals(Object ob) {                                     
            if (ob == null) return false;                                      
            if (!(ob instanceof RegistryKey)) return false;                    
            RegistryKey other = (RegistryKey)ob;                               
            if (this.name != other.name) return false;                         
            if (this.family != other.family) return false;                     
            return true;                                                       
        }                                                                      
    }       
    
    private static class LazyInitialization {                                  
        static final Map<RegistryKey,OptionKey> options = options();   
                
        private static Map<RegistryKey,OptionKey> options() {                  
            Map<RegistryKey,OptionKey> map =                                   
                new HashMap<RegistryKey,OptionKey>();                          
            map.put(new RegistryKey(StandardSocketOptions.SO_BROADCAST, UnixNet.UNSPEC), new OptionKey(SOL_SOCKET, SO_BROADCAST));
            map.put(new RegistryKey(StandardSocketOptions.SO_KEEPALIVE, UnixNet.UNSPEC), new OptionKey(SOL_SOCKET, SO_KEEPALIVE));
            map.put(new RegistryKey(StandardSocketOptions.SO_LINGER, UnixNet.UNSPEC), new OptionKey(SOL_SOCKET, SO_LINGER));
            map.put(new RegistryKey(StandardSocketOptions.SO_SNDBUF, UnixNet.UNSPEC), new OptionKey(SOL_SOCKET, SO_SNDBUF));
            map.put(new RegistryKey(StandardSocketOptions.SO_RCVBUF, UnixNet.UNSPEC), new OptionKey(SOL_SOCKET, SO_RCVBUF));
            map.put(new RegistryKey(StandardSocketOptions.SO_REUSEADDR, UnixNet.UNSPEC), new OptionKey(SOL_SOCKET, SO_REUSEADDR));
            map.put(new RegistryKey(StandardSocketOptions.TCP_NODELAY, UnixNet.UNSPEC), new OptionKey(6, TCP_NODELAY));
            map.put(new RegistryKey(StandardSocketOptions.IP_TOS, StandardProtocolFamily.INET), new OptionKey(0, IP_TOS));
            map.put(new RegistryKey(StandardSocketOptions.IP_MULTICAST_IF, StandardProtocolFamily.INET), new OptionKey(0, IP_MULTICAST_IF));
            map.put(new RegistryKey(StandardSocketOptions.IP_MULTICAST_TTL, StandardProtocolFamily.INET), new OptionKey(0, IP_MULTICAST_TTL));
            map.put(new RegistryKey(StandardSocketOptions.IP_MULTICAST_LOOP, StandardProtocolFamily.INET), new OptionKey(0, IP_MULTICAST_LOOP));
            map.put(new RegistryKey(StandardSocketOptions.IP_MULTICAST_IF, StandardProtocolFamily.INET6), new OptionKey(41, IPV6_MULTICAST_IF));
            map.put(new RegistryKey(StandardSocketOptions.IP_MULTICAST_TTL, StandardProtocolFamily.INET6), new OptionKey(41, IPV6_MULTICAST_HOPS));
            map.put(new RegistryKey(StandardSocketOptions.IP_MULTICAST_LOOP, StandardProtocolFamily.INET6), new OptionKey(41, IPV6_MULTICAST_LOOP));
            return map;                                                        
        }                                                                      
    }                                                                          
    public static OptionKey findOption(SocketOption<?> name, ProtocolFamily family) { 
        RegistryKey key = new RegistryKey(name, family);                       
        return LazyInitialization.options.get(key);                            
    }                                                                          
}
