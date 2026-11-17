Remove-Item *.class -ErrorAction SilentlyContinue; javac -cp ".;lib/mysql-connector-j-26.7.0.jar" *.java; java -cp ".;lib/mysql-connector-j-26.7.0.jar" BloodDonationApp
