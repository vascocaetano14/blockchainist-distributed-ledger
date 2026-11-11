package pt.tecnico.blockchainist.sequencer;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import pt.tecnico.blockchainist.sequencer.domain.SequencerState;
import pt.tecnico.blockchainist.sequencer.grpc.SequencerServiceImpl;

import java.io.IOException;

public class SequencerMain {

    public static void main(String[] args) throws IOException, InterruptedException {
        System.out.println(SequencerMain.class.getSimpleName());

        if (args.length < 1) {
            System.err.println("Usage: SequencerMain <port> [N] [T] [-debug]");
            return;
        }

        final int port = Integer.parseInt(args[0]);

        int n = 4;
        int t = 5;

        if (args.length >=3){
            n = Integer.parseInt(args[1]);
            t = Integer.parseInt(args[2]);
        }
        
        boolean debug = System.getProperty("debug") != null;
        
        SequencerState state = new SequencerState(n, t);
        SequencerServiceImpl serviceImpl = new SequencerServiceImpl(state, debug);

        Server server = ServerBuilder.forPort(port).addService(serviceImpl).build();

        server.start();
        System.out.println("Sequencer started on port " + port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down sequencer...");
            state.shutdown();
            server.shutdown();
        }));

        server.awaitTermination(); 
    }
}