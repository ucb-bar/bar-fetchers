#!/usr/bin/python3

import sys
import argparse
import re

"""
Lines formatting:
Cycle: decimal_int  SnoopAddr: hexadecimal_int  SnoopBlockAddr: hexadecimal_int
Cycle: decimal_int  SnoopRespAddr: hexadecimal_int
Cycle: decimal_int  PrefetchAddr: hexadecimal_int
Cycle: decimal_int  PrefetchRespAddr: hexadecimal_int
"""

snoop_regex = re.compile("Cycle: +(\d+)\sSnoopAddr: ([\da-f]+)\sSnoopBlock: ([\da-f]+).*")
resp_regex = re.compile("Cycle: +(\d+)\sSnoopRespAddr: ([\da-f]+).*")
prefetch_regex = re.compile("Cycle: +(\d+)\sPrefetchAddr: ([\da-f]+).*")
prefetch_resp_regex = re.compile("Cycle: +(\d+)\sPrefetchRespAddr: ([\da-f]+).*")


def main():
    parser = argparse.ArgumentParser(description="Specify input files")
    parser.add_argument('prefetch_file', type=str, help='input file for the prefetch config')
    parser.add_argument('no_prefetch_file', type=str, help='input file for the non-prefetch config')
    args = parser.parse_args()

    with open(args.prefetch_file) as f:
        prefetch_lines = f.readlines()
    with open(args.no_prefetch_file) as f:
        no_prefetch_lines = f.readlines()
    
    misses_prevented = 0
    prefetch_queue={}
    prefetches_sent=[]

    no_prefetch = classify_accesses(no_prefetch_lines)
    no_prefetch_misses = no_prefetch['misses']
    no_prefetch_hits = no_prefetch['hits']
    with_prefetch = classify_accesses(prefetch_lines)
    with_prefetch_hits = with_prefetch['hits']
    with_prefetch_misses = with_prefetch['misses']

    print("Misses without prefetcher: " + str(len(no_prefetch_misses)))
    print("Misses with prefetcher: " + str(len(with_prefetch_misses)))

    prefetch_hits_only = list(with_prefetch_hits)
    no_prefetch_misses_only = list(no_prefetch_misses)

    for addr in no_prefetch_hits:
        if addr in prefetch_hits_only:
            prefetch_hits_only.remove(addr) #get only new hits, blind to duplicates
    for addr in with_prefetch_misses:
        if addr in no_prefetch_misses_only:
            no_prefetch_misses_only.remove(addr)

    useful_prefetches=[] #prefetches that actually prevent a miss
    num_prefetch_resps = 0
    num_unique_prefetch_resps = 0
    delta_sum = 0
    num_prefetches_accessed = 0

    for line in prefetch_lines:
        pref = prefetch_regex.match(line)
        pref_resp = prefetch_resp_regex.match(line)
        snoop = snoop_regex.match(line)
        if pref:
            prefetches_sent.append(int(pref.group(2), 16)) #add new prefetch address
        elif pref_resp:
            pref_resp_addr = int(pref_resp.group(2), 16)
            pref_resp_cycles = int(pref_resp.group(1))
            if pref_resp_addr in prefetches_sent:
                if (pref_resp_addr not in prefetch_queue):
                    num_unique_prefetch_resps += 1
                prefetch_queue[pref_resp_addr] = pref_resp_cycles #only interested in most recent response timing
                num_prefetch_resps += 1
        elif snoop:
            addr = int(snoop.group(3), 16) #get block address
            cycles = int(snoop.group(1))
            if (addr in prefetch_queue):
                delta_sum += (cycles - prefetch_queue[addr])
                num_prefetches_accessed += 1
                if ((addr in no_prefetch_misses_only) and (addr in prefetch_hits_only)):
                    no_prefetch_misses_only.remove(addr) # make sure miss isn't counted twice
                    prefetch_hits_only.remove(addr)
                    misses_prevented += 1
                    useful_prefetches.append(snoop.group(3))
    
    #Accuracy Calculations
    num_no_resp_prefetches=len(prefetches_sent)-num_prefetch_resps
    num_unused_prefetches=num_prefetch_resps-len(useful_prefetches)
    useless_prefetches = num_no_resp_prefetches + num_unused_prefetches

    print("misses prevented: " + str(misses_prevented))
    coverage = float(misses_prevented) / (misses_prevented + len(with_prefetch_misses)) * 100
    print("coverage: " + str(coverage) + "%")
    # Split into acknowledged and sent prefetches
    accuracy_all = float(misses_prevented) / (useless_prefetches + misses_prevented) * 100
    print("accuracy: " + str(accuracy_all) + "%")
    #TODO: must not count duplicates
    accuracy_resp = float(misses_prevented) / (num_unique_prefetch_resps-len(useful_prefetches) + misses_prevented) * 100
    print("accuracy (acknowledged): " + str(accuracy_resp) + "%")
    if (num_prefetches_accessed != 0):
        timeliness = float(delta_sum) / num_prefetches_accessed
        print("timeliness: " + str(timeliness) + " cycles")


def classify_accesses(lines):
    snoops = {}
    all_addr = []
    accesses = {"hits": [], "misses": []}
    last_resp_cycle = 0
    for line in lines:
        resp = resp_regex.match(line)
        snoop = snoop_regex.match(line)
        if snoop:
            snoop_cycles = int(snoop.group(1))
            addr = snoop.group(2)
            snoop_block = int(snoop.group(3), 16)
            #use absolute addr in case of backlogged accesses to same block
            snoops[addr] = (snoop_cycles, snoop_block)
            all_addr.append(addr)
        elif resp:
            #check against snoops
            resp_cycles = int(resp.group(1))
            resp_addr = resp.group(2)
            if (resp_addr in snoops): 
                if ((resp_cycles - snoops[resp_addr][0] >= 5) and (resp_cycles - last_resp_cycle > 3)):
                    accesses["misses"].append(snoops[resp_addr][1]) #add snoop block addr to misses
                else:
                    accesses["hits"].append(snoops[resp_addr][1])
                snoops.pop(resp_addr)
                last_resp_cycle = int(resp.group(1))
    return accesses


if __name__ == "__main__":
  main()
