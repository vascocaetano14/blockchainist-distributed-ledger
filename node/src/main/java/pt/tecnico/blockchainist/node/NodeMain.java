package pt.tecnico.blockchainist.node;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import pt.tecnico.blockchainist.node.grpc.NodeServiceImpl;

import java.io.IOException;


public class NodeMain {
    public static void main(String[] args) throws IOException, InterruptedException {

        System.out.println(NodeMain.class.getSimpleName());

        // check arguments
        if (args.length < 3) {
            System.err.println("Argument(s) missing!");
            return;
        }
        
        final int port = Integer.parseInt(args[0]); //porto onde o nó corre

        final String orgId = args[1];    //Identificador organização

        //Parse da string formatada local host
        final String[] seqAddress = args[2].split(":");
        final String seqHost = seqAddress[0];
        final int seqPort = Integer.parseInt(seqAddress[1]);

        boolean debug = System.getProperty("debug") != null;

        final NodeServiceImpl serviceImpl = new NodeServiceImpl(orgId, seqHost, seqPort, debug);

        // Create a new server to listen on port
        final Server server = ServerBuilder.forPort(port)
            .addService(io.grpc.ServerInterceptors.intercept(serviceImpl, new pt.tecnico.blockchainist.node.grpc.NodeDelayInterceptor()))
                .build();

        // Start the server
        server.start();
        serviceImpl.initialSync();
        System.out.println("Node started on port " + port);

        //ShutodownHook para quando a JVM inicializa processo de shutdown -> vai desligar processos de serviço e server
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            serviceImpl.shutdown();
            server.shutdown();
        }));


        // Do not exit the main thread. Wait until server is terminated.
        server.awaitTermination();
    }
}
