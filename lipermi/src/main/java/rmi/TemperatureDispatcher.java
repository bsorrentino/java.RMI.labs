package rmi;

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface TemperatureDispatcher extends Remote
{

    void addTemperatureListener(TemperatureListener addTemperatureListener) throws RemoteException;

    void removeTemperatureListener(TemperatureListener addTemperatureListener) throws RemoteException;

    Double getTemperature() throws RemoteException;
}