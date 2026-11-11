package pt.tecnico.blockchainist.client;

import java.security.PrivateKey;
import java.util.ArrayList;

import pt.tecnico.blockchainist.client.grpc.ClientNodeService;
import pt.tecnico.blockchainist.contract.CryptoUtils;

public class ClientMain {

    public static void main(String[] args) {

        System.out.println(ClientMain.class.getSimpleName());
        
        // check arguments
        if (args.length < 1) {
            System.err.println("Argument(s) missing!");
            printUsage();
            return;
        }

        // parse arguments
        boolean debug=System.getProperty("debug") != null;

        ArrayList<ClientNodeService> nodes = new ArrayList<>(args.length);
        for (String arg : args) {
                                                                   
            String[] split = arg.split(":");
            if (split.length != 3) {
                System.err.println("Invalid argument: " + arg);
                printUsage();
                return;
            }
            String host = split[0];
            int port = -1;
            try {
                port = Integer.parseInt(split[1]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port (" + split[1] + ") in argument: " + arg);
                printUsage();
                return;
            }
            if (port > 65535 || port < 0) {
                System.err.println("Port number out of range (0-65535): " + port);
                printUsage();
                return;
            }
            String organization = split[2];

            nodes.add(new ClientNodeService(host, port, organization));
        }

        if (nodes.isEmpty()) {                                          
            System.err.println("No node addresses provided!");          
            printUsage();                                               
            return;                                                     
        }

        //autentica o utilizador via -Duser.id=nome, se omitido, opera sem assinaturas
        String userId = System.getProperty("user.id");  
        PrivateKey privateKey = null;
        if (userId != null && !userId.isBlank()) {
            try {
                privateKey = CryptoUtils.readPrivateKey("/" + userId + ".priv");
            } catch (Exception e) {
                throw new RuntimeException("Failed to load private key for user " + userId, e);
            }   
        }
        CommandProcessor processor = new CommandProcessor(nodes, debug, userId, privateKey);

        processor.userInputLoop();

        // shutdown all gRPC channels
        for (ClientNodeService node : nodes) {
            node.shutdown();
        }
    }

    private static void printUsage() {
        System.err.println("Usage: mvn exec:java -Dexec.args=\"<host>:<port>:<organization> [<host>:<port>:<organization> ...]\"");
    }
}
