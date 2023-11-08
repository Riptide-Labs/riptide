import pyshark
import click
import socket
import time
import tqdm


@click.command()
@click.argument('capture', type=click.Path(exists=True))
def main(capture):
    with socket.socket(socket.AF_INET,
                       socket.SOCK_DGRAM) as sock:

        with pyshark.FileCapture(capture) as cap:
            #cap.load_packets()

            for pkt in tqdm.tqdm(cap):
                if 'cflow' not in pkt:
                    continue
                
                sock.sendto(bytes.fromhex(pkt.udp.payload.raw_value), ('127.0.0.1', 9999))
                time.sleep(0.01)


if __name__ == '__main__':
    main()

