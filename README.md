# Chỉ đường xe tại Việt Nam theo thuật toán Graphhopper

![image](https://res.cloudinary.com/dsobei3hp/image/upload/v1609812681/GitHub/Untitled_nwywch.png)
##

## Cài đặt 
https://github.com/graphhopper/graphhopper/blob/0.13/docs/core/windows-setup.md
### Cần môi trường JAVA
 * Make sure you have the latest JDK installed and not only the JRE
 * For me JAVA_HOME was not correct so I had to overwrite it before calling
   the `graphhopper.sh` script:
   ```bash
   export JAVA_HOME=/cygdrive/c/Programme/Java/jdk1.8.0_77
   ```
##

###
$ ./graphhopper.sh -a web -i europe_germany_berlin.pbf
------------
### europe_germany_berlin.pbf Là file dữ liệu bản đồ theo khu vực
