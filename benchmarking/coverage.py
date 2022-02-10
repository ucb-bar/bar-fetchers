def main():
    #TODO: make parameterizable, write bash script to run configs automatically
    with open('prefetch_vvadd.out') as f:
        prefetch_lines = f.readlines()

    with open('vvadd.out') as f:
        no_prefetch_lines = f.readlines()

    misses_prevented = 0
    prefetch_queue=[]

    no_prefetch = classify_accesses(no_prefetch_lines)
    no_prefetch_misses = no_prefetch['misses']
    no_prefetch_hits = no_prefetch['hits']
    with_prefetch = classify_accesses(prefetch_lines)
    with_prefetch_hits = with_prefetch['hits']
    with_prefetch_misses = with_prefetch['misses']
    print(len(no_prefetch['hits']))
    print(len(with_prefetch_hits))
    print(len(no_prefetch_misses))
    print(len(with_prefetch_misses))
    for addr in with_prefetch_hits:
        if not addr in no_prefetch_hits:
            print(addr)
    #print(no_prefetch_misses)
    #print(with_prefetch_misses)



    # for miss in no_prefetch_misses['misses']:
    #     print('miss: ' + miss)
    # for hit in no_prefetch_misses['hits']:
    #     print('hit: ' + hit)

    for line in prefetch_lines:
        if "Prefetch" in line:
            pref = line.split()
            if len(prefetch_queue) == 20:
                prefetch_queue.pop() #remove least recently added addr
            prefetch_queue.append(pref[4]) #add new prefetch address to queue
        if "Snoop" in line:
            snoop = line.split()
            addr = snoop[4]
            # TODO: account for the same address being accessed more than once
            # Look at L2
            # Bucket same accesses seperately, separate ambiguous misses prevented from clear misses prevented
                # just handle ambiguous seperately using L2
            if (addr in prefetch_queue) and (addr in no_prefetch_misses) and (addr in with_prefetch_hits):
                misses_prevented += 1

    print("misses prevented: " + str(misses_prevented))
    coverage = (misses_prevented + 0.0) / (misses_prevented + len(with_prefetch_misses)) * 100
    print("coverage: " + str(coverage) + "%")



def classify_accesses(lines):
    snoops = {}
    all_addr = []
    accesses = {"hits": [], "misses": []}
    for line in lines:
        if 'Snoop' in line:
            snoop = line.split()
            id = snoop[8]
            cycles = snoop[1]
            addr = snoop[4]
            snoops[id] = [cycles, addr]
            all_addr.append(addr)
        elif 'Response' in line:
            #check against snoops
            resp = line.split()
            resp_id = resp[4]
            if (resp_id in snoops): 
                if ((int(resp[1]) - int(snoops[resp_id][0])) >= 10):
                    accesses["misses"].append(snoops[resp_id][1]) #add snoop addr to misses
                else:
                    accesses["hits"].append(snoops[resp_id][1])
                snoops.pop(resp_id)
    #print(all_addr)
    return accesses

main()