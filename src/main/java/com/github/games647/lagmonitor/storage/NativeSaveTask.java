package com.github.games647.lagmonitor.storage;

import com.github.games647.lagmonitor.LagMonitor;
import com.github.games647.lagmonitor.traffic.TrafficReader;
import org.hyperic.sigar.FileSystemUsage;
import org.hyperic.sigar.NetInterfaceStat;
import org.hyperic.sigar.Sigar;
import org.hyperic.sigar.SigarException;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.logging.Level;

public class NativeSaveTask implements Runnable {

    private final LagMonitor plugin;

    private long lastCheck = System.currentTimeMillis();

    private int lastMcRead = 0;
    private int lastMcWrite = 0;
    private int lastDiskRead = 0;
    private int lastDiskWrite = 0;
    private int lastNetRead = 0;
    private int lastNetWrite = 0;

    public NativeSaveTask(LagMonitor plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        long currentTime = System.currentTimeMillis();
        int timeDiff = (int) (currentTime - lastCheck) / 1_000;

        TrafficReader trafficReader = plugin.getTrafficReader();
        int mcReadDiff = 0;
        int mcWriteDiff = 0;
        if (trafficReader != null) {
            int mcRead = byteToMega(trafficReader.getIncomingBytes().get());
            mcReadDiff = (mcRead - lastMcRead) / timeDiff;
            lastMcRead = mcRead;

            int mcWrite = byteToMega(trafficReader.getOutgoingBytes().get());
            mcWriteDiff = (mcWrite - lastMcWrite) / timeDiff;
            lastMcWrite = mcWrite;
        }

        long freeSpace = 0;
        long totalSpace = 0;
        try {
            FileStore fileStore = Files.getFileStore(Paths.get("."));
            freeSpace = fileStore.getUsableSpace();
            totalSpace = fileStore.getUsableSpace();
        } catch (IOException ioEx) {
            plugin.getLogger().log(Level.WARNING, "Cannot calculate free/total disk space", ioEx);
        }

        totalSpace = byteToMega(totalSpace);
        freeSpace = byteToMega(freeSpace);

        //4 decimal places -> Example: 0.2456
        float freeSpacePct = round((freeSpace * 100 / (float) totalSpace), 4);

        int diskReadDiff = 0;
        int diskWriteDiff = 0;
        int netReadDiff = 0;
        int netWriteDiff = 0;

        Sigar sigar = plugin.getNativeData().getSigar();
        if (sigar != null) {
            try {
                String rootFileSystem = Paths.get(".").getRoot().toAbsolutePath().toString();
                FileSystemUsage fileSystemUsage = sigar.getFileSystemUsage(rootFileSystem);
                int diskRead = byteToMega(fileSystemUsage.getDiskReadBytes());
                diskReadDiff = (diskRead - lastDiskRead) / timeDiff;
                lastDiskRead = diskRead;

                int diskWrite = byteToMega(fileSystemUsage.getDiskWriteBytes());
                diskWriteDiff = (diskWrite - lastDiskWrite) / timeDiff;
                lastDiskWrite = diskRead;

                NetInterfaceStat usedNetInterfaceStat = findNetworkInterface(sigar);
                if (usedNetInterfaceStat != null) {
                    int netRead = byteToMega(usedNetInterfaceStat.getRxBytes());
                    netReadDiff = (netRead - lastNetRead) / timeDiff;
                    lastNetRead = netRead;

                    int netWrite = byteToMega(usedNetInterfaceStat.getTxBytes());
                    netWriteDiff = (netWrite - lastNetWrite) / timeDiff;
                    lastNetWrite = netWrite;
                }
            } catch (SigarException ex) {
                plugin.getLogger().log(Level.SEVERE, "Failed to get the disk read/writer for database monitoring", ex);
            }
        }

        lastCheck = currentTime;

        plugin.getStorage().saveNative(mcReadDiff, mcWriteDiff, freeSpace, freeSpacePct, diskReadDiff, diskWriteDiff
                , netReadDiff, netWriteDiff);
    }

    private NetInterfaceStat findNetworkInterface(Sigar sigar) throws SigarException {
        NetInterfaceStat usedNetInterfaceStat = null;
        String[] netInterfaceList = sigar.getNetInterfaceList();
        for (String interfaceName : netInterfaceList) {
            NetInterfaceStat interfaceStat = sigar.getNetInterfaceStat(interfaceName);
            if (interfaceStat.getRxBytes() != 0) {
                usedNetInterfaceStat = interfaceStat;
                break;
            }
        }

        return usedNetInterfaceStat;
    }

    private float round(double value, int places) {
        BigDecimal bd = new BigDecimal(value);
        bd = bd.setScale(places, RoundingMode.HALF_UP);
        return bd.floatValue();
    }

    private int byteToMega(long bytes) {
        return (int) (bytes / (1024 * 1024));
    }
}
