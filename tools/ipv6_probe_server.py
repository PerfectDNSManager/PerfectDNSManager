import socket
with socket.socket(socket.AF_INET6, socket.SOCK_STREAM) as server:
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(('::1', 18089))
    server.listen(8)
    print('IPv6 probe ready on [::1]:18089', flush=True)
    while True:
        conn, addr = server.accept()
        with conn:
            conn.sendall(b'PDM_IPV6_OK\n')
