package net.appstorefr.perfectdnsmanager;

interface IShellService {
    String exec(String command);
    void destroy();
}
