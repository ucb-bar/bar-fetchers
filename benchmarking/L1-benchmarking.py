import sys

def main():
    with open(sys.argv[1]) as f:
        prefetch_lines = f.readlines()

    with open(sys.argv[2]) as f:
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
    delta_sum = 0
    num_prefetches_accessed = 0

    for line in prefetch_lines:
        if "Prefetch Addr" in line:
            pref = line.split()
            prefetches_sent.append(pref[4]) #add new prefetch address
        elif "Prefetch Resp" in line:
            pref_resp = line.split()
            pref_resp_addr = pref_resp[5]
            pref_resp_cycles = int(pref_resp[1])
            if pref_resp_addr in prefetches_sent:
                prefetch_queue[pref_resp_addr] = pref_resp_cycles #only interested in most recent response timing
                num_prefetch_resps += 1
        elif "Snoop" in line:
            snoop = line.split()
            addr = snoop[4]
            cycles = int(snoop[1])
            if (addr in prefetch_queue):
                delta_sum += (cycles - prefetch_queue[addr])
                num_prefetches_accessed += 1
                if ((addr in no_prefetch_misses_only) and (addr in prefetch_hits_only)):
                    no_prefetch_misses_only.remove(addr) # make sure miss isn't counted twice
                    prefetch_hits_only.remove(addr)
                    misses_prevented += 1
                    useful_prefetches.append(addr)
    
    #Accuracy Calculations
    num_no_resp_prefetches=len(prefetches_sent)-num_prefetch_resps
    num_unused_prefetches=num_prefetch_resps-len(useful_prefetches)
    useless_prefetches = num_no_resp_prefetches + num_unused_prefetches

    print("misses prevented: " + str(misses_prevented))

    coverage = (misses_prevented + 0.0) / (misses_prevented + len(with_prefetch_misses)) * 100
    print("coverage: " + str(coverage) + "%")

    accuracy = (misses_prevented + 0.0) / (useless_prefetches + misses_prevented) * 100
    print("accuracy: " + str(accuracy) + "%")

    timeliness = (delta_sum + 0.0) / num_prefetches_accessed
    print("timeliness: " + str(timeliness) + " cycles")



def classify_accesses(lines):
    snoops = {}
    all_addr = []
    accesses = {"hits": [], "misses": []}
    last_resp_cycle = 0
    for line in lines:
        if 'Snoop' in line:
            snoop = line.split()
            snoop_cycles = snoop[1]
            addr = snoop[4]
            snoops[addr] = snoop_cycles
            all_addr.append(addr)
        elif 'Resp' in line:
            #check against snoops
            resp = line.split()
            resp_cycles = resp[1]
            resp_addr = resp[4]
            if (resp_addr in snoops): 
                if (((int(resp_cycles) - int(snoops[resp_addr])) >= 5) and (int(resp_cycles) - int(last_resp_cycle) > 3)):
                    accesses["misses"].append(resp_addr) #add snoop addr to misses
                else:
                    accesses["hits"].append(resp_addr)
                snoops.pop(resp_addr)
                last_resp_cycle = resp[1]
    return accesses



main()