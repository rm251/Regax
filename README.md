//Check string contains only digit
                String s="12345678";
        System.out.println(s.matches("\\d+"));

        //validate mobile number
        String phone="9863214560";
        System.out.println(phone.matches("^[6-9]\\d{9}$"));

        //validate email
        String email="ram123@gmail.com";
        System.out.println(email.matches("^[A-Za-z0-9+_.-]+@(.+)$"));

        //find numbers in string
        String s="Ram123shyam456";
        Pattern p=Pattern.compile("\\d+");
        Matcher m=p.matcher(s);
        while(m.find()){
            System.out.println(m.group());//123,456
        }

        //remove digits
        String s="Hello12367";
        System.out.println(s.replaceAll("\\d",""));//hello
