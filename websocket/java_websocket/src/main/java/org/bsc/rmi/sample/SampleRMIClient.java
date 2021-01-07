package org.bsc.rmi.sample;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.bsc.rmi.websocket.RMIClientWebsocketFactory;

import java.io.IOException;
import java.net.Socket;
import java.rmi.registry.Registry;
import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RMISocketFactory;
import java.util.concurrent.CompletableFuture;

/**
 * RMI client to invoke calls through the ServletHandler
 */
@Slf4j
public class SampleRMIClient {


    /**
     * @param host
     * @param port
     * @return
     */
    private static CompletableFuture<Registry> getRMIRegistry(@NonNull String host, int port) {

        final RMIClientSocketFactory clientSocketFactory[] = {
            RMISocketFactory.getDefaultSocketFactory(),
            new RMIClientWebsocketFactory()
        };

        CompletableFuture<Registry> result = new CompletableFuture<>();

        try {
            final Registry reg = java.rmi.registry.LocateRegistry.getRegistry(host, port, clientSocketFactory[1]);

            result.complete(reg);

        } catch (Exception e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    private static CompletableFuture<SampleRMI> lookup(Registry reg) {
        CompletableFuture<SampleRMI> result = new CompletableFuture<>();
        try {
            final SampleRMI robject = (SampleRMI) reg.lookup("SampleRMI");
            result.complete(robject);
        } catch (Exception e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    private static CompletableFuture<Void> call(SampleRMI robject) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {

            for (int time = 1; time <= 10; ++time) {

                final String justPassResult = robject.justPass("This is a test of the RMI servlet handler");
                log.info("#{} - sampleRMI.justPass()={}", time, justPassResult);

                final String info = robject.getInfo();
                log.info("#{} - sampleRMI.getInfo()={}", time, info);

                Thread.sleep(1000);
            }
            result.complete(null);

        } catch (Exception e) {
            result.completeExceptionally(e);
        }
        return result;
    }


    public static void main(String args[]) {

        final String host = (args.length < 1) ? "localhost" : args[0];

        log.info(System.getProperty("java.security.policy"));

        System.setSecurityManager(new SecurityManager());

        int rmi_port = 1099;
        int websocket_port = 8887;

        getRMIRegistry(host, websocket_port)
                .thenCompose(SampleRMIClient::lookup)
                .thenCompose(SampleRMIClient::call)
                .exceptionally(e -> {
                    log.error("error", e);
                    //log.throwing(SampleRMIClient.class.getName(), "main", e);
                    return null;
                })
                .join();

        System.exit(0);
    }
}